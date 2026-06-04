/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.salt.jlangchain.core.subagent;

import lombok.extern.slf4j.Slf4j;
import org.salt.jlangchain.core.ChainActor;
import org.salt.jlangchain.core.agent.McpAgentExecutor;
import org.salt.jlangchain.core.llm.BaseChatModel;
import org.salt.jlangchain.core.skill.Skill;
import org.salt.jlangchain.core.skill.SkillConfig;
import org.salt.jlangchain.rag.tools.Tool;

import org.salt.function.flow.context.ContextBus;
import org.salt.jlangchain.core.common.CallInfo;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * A sub-agent is a self-contained, isolated agent unit that:
 * <ol>
 *   <li><b>Owns its tools</b> — declared via {@link Builder#tools(Tool...)}</li>
 *   <li><b>Borrows parent tools</b> — declared via {@code tools} in AGENT.md frontmatter,
 *       injected by the master agent at registration time (like Skill's {@code allowedTools})</li>
 *   <li><b>Pre-loads skill knowledge</b> — each {@link SkillConfig} baked into systemPrompt</li>
 *   <li><b>Flexible LLM resolution</b> — priority: {@code .llm()} > {@code model=inherit} (injected)
 *       > {@code model=<name>} (resolved via {@code llmFactory})</li>
 * </ol>
 *
 * <pre>{@code
 * SubAgentConfig config = ClasspathSubAgentConfigLoader.fromClasspath("agents/travel-researcher");
 * SubAgent researcher = SubAgent.from(config, chainActor)
 *     .llm(llm)
 *     .tools(weatherTool, flightTool)
 *     .verbose(true)
 *     .build();
 *
 * McpAgentExecutor master = McpAgentExecutor.builder(chainActor)
 *     .llm(masterLlm)
 *     .subAgent(researcher)
 *     .build();
 * }</pre>
 */
@Slf4j
public class SubAgent {

    private final SubAgentConfig config;
    private final ChainActor chainActor;
    private final BaseChatModel llm;
    private final Function<String, BaseChatModel> llmFactory;
    private final List<Tool> ownTools;
    private final List<Skill> callableSkills;
    private final int maxIterations;
    private final Consumer<String> onLlm;
    private final Consumer<String> onToolCall;
    private final Consumer<String> onObservation;

    // injected after construction by the parent agent
    private volatile BaseChatModel injectedLlm;
    private volatile List<Tool> inheritedTools = List.of();
    private volatile McpAgentExecutor executor;

    private SubAgent(SubAgentConfig config, ChainActor chainActor, BaseChatModel llm,
                     Function<String, BaseChatModel> llmFactory,
                     List<Tool> ownTools, List<Skill> callableSkills, int maxIterations,
                     Consumer<String> onLlm, Consumer<String> onToolCall, Consumer<String> onObservation) {
        this.config = config;
        this.chainActor = chainActor;
        this.llm = llm;
        this.llmFactory = llmFactory;
        this.ownTools = new ArrayList<>(ownTools);
        this.callableSkills = new ArrayList<>(callableSkills);
        this.maxIterations = maxIterations;
        this.onLlm = onLlm;
        this.onToolCall = onToolCall;
        this.onObservation = onObservation;
    }

    public static Builder from(SubAgentConfig config, ChainActor chainActor) {
        return new Builder(config, chainActor);
    }

    // ── metadata ─────────────────────────────────────────────────────────────

    public String getName() { return config.getName(); }

    public String getDescription() { return config.getDescription(); }

    public boolean isInheritModel() { return config.isInheritModel(); }

    public List<String> getAllowedTools() {
        return config.getAllowedTools() != null ? config.getAllowedTools() : List.of();
    }

    // ── injection (called by McpAgentExecutor.Builder) ───────────────────────

    /** Inject master agent's LLM when {@code model=inherit}. */
    public void injectLlm(BaseChatModel llm) {
        this.injectedLlm = llm;
        this.executor = null;
    }

    /** Inject a factory so this sub-agent can build its own LLM from {@code model} name. */
    public void injectLlmFactory(Function<String, BaseChatModel> factory) {
        // only used if no explicit llm was set in builder
        if (this.llm == null && !config.isInheritModel()) {
            this.injectedLlm = factory.apply(config.getModel());
            this.executor = null;
        }
    }

    /** Inject tools from parent filtered by {@code allowedTools}. */
    public void injectParentTools(List<Tool> tools) {
        this.inheritedTools = new ArrayList<>(tools);
        this.executor = null;
    }

    // ── public API ───────────────────────────────────────────────────────────

    /** Wraps this sub-agent as a Tool for use in a master agent's tool registry. */
    public Tool asTool() {
        return Tool.builder()
                .name(getName())
                .description(getDescription())
                .params("input: String")
                .func(input -> invoke(input != null ? input.toString() : ""))
                .build();
    }

    public String invoke(String input) {
        if (executor == null) {
            synchronized (this) {
                if (executor == null) {
                    executor = buildExecutor();
                }
            }
        }
        // Propagate parent's stop signal so a master stop cascades into this sub-agent
        AtomicBoolean parentSignal = null;
        if (ContextBus.get() != null) {
            parentSignal = ((org.salt.function.flow.context.IContextBus) ContextBus.get())
                    .getTransmit(CallInfo.STOP_SIGNAL.name());
        }
        return executor.invoke(input, parentSignal).getText();
    }

    // ── internal executor construction ───────────────────────────────────────

    private McpAgentExecutor buildExecutor() {
        List<Tool> allTools = collectTools();

        BaseChatModel resolvedLlm = resolveLlm();
        if (resolvedLlm == null) {
            throw new IllegalStateException(
                    "SubAgent '" + getName() + "' has no LLM. " +
                    (config.isInheritModel()
                        ? "model=inherit: parent agent must register this sub-agent before invoking."
                        : config.getModel() != null
                            ? "model=" + config.getModel() + ": provide llmFactory in McpAgentExecutor.builder()."
                            : "Call .llm() in the builder."));
        }

        log.debug("Building executor for sub-agent '{}' with {} tool(s), {} callable skill(s)",
                getName(), allTools.size(), callableSkills.size());

        var builder = McpAgentExecutor.builder(chainActor)
                .llm(resolvedLlm)
                .systemPrompt(buildSystemPrompt())
                .tools(allTools)
                .maxIterations(maxIterations);

        for (Skill skill : callableSkills) {
            builder.skill(skill);
        }

        if (onLlm != null)          builder.onLlm(onLlm);
        if (onToolCall != null)     builder.onToolCall(onToolCall);
        if (onObservation != null)  builder.onObservation(onObservation);

        return builder.build();
    }

    private BaseChatModel resolveLlm() {
        if (llm != null) return llm;            // highest priority: explicit builder.llm()
        if (injectedLlm != null) return injectedLlm;  // inherit or factory-resolved
        if (llmFactory != null && config.getModel() != null && !config.isInheritModel()) {
            return llmFactory.apply(config.getModel());
        }
        return null;
    }

    private List<Tool> collectTools() {
        List<Tool> all = new ArrayList<>(ownTools);
        all.addAll(inheritedTools);
        return all;
    }

    private String buildSystemPrompt() {
        StringBuilder sb = new StringBuilder();
        if (config.getSystemPrompt() != null && !config.getSystemPrompt().isBlank()) {
            sb.append(config.getSystemPrompt());
        }
        if (config.getSkills() != null) {
            for (SkillConfig skillConfig : config.getSkills()) {
                sb.append("\n\n---\n\n## Skill Reference: ").append(skillConfig.getName()).append("\n\n");
                if (skillConfig.getSystemPrompt() != null) {
                    sb.append(skillConfig.getSystemPrompt());
                }
                if (skillConfig.getReferences() != null) {
                    for (String ref : skillConfig.getReferences()) {
                        sb.append("\n\n").append(ref);
                    }
                }
            }
        }
        return sb.toString();
    }

    // ── builder ──────────────────────────────────────────────────────────────

    public static class Builder {

        private static final int DEFAULT_MAX_ITERATIONS = 10;

        private final SubAgentConfig config;
        private final ChainActor chainActor;
        private BaseChatModel llm;
        private Function<String, BaseChatModel> llmFactory;
        private final List<Tool> ownTools = new ArrayList<>();
        private final List<Skill> callableSkills = new ArrayList<>();
        private Integer maxIterations;
        private Consumer<String> onLlm;
        private Consumer<String> onToolCall;
        private Consumer<String> onObservation;

        private Builder(SubAgentConfig config, ChainActor chainActor) {
            this.config = config;
            this.chainActor = chainActor;
        }

        public Builder llm(BaseChatModel llm) {
            this.llm = llm;
            return this;
        }

        /**
         * Provide a factory that converts a model name string to a {@link BaseChatModel}.
         * Used when AGENT.md specifies {@code model: qwen-plus} (or any non-inherit value)
         * and no explicit {@link #llm(BaseChatModel)} is set.
         */
        public Builder llmFactory(Function<String, BaseChatModel> factory) {
            this.llmFactory = factory;
            return this;
        }

        public Builder tools(Tool... tools) {
            this.ownTools.addAll(List.of(tools));
            return this;
        }

        public Builder tools(List<Tool> tools) {
            this.ownTools.addAll(tools);
            return this;
        }

        /** Register a {@link Skill} as a callable tool inside this sub-agent. */
        public Builder skill(Skill skill) {
            this.callableSkills.add(skill);
            return this;
        }

        public Builder skills(Skill... skills) {
            this.callableSkills.addAll(List.of(skills));
            return this;
        }

        /** Override max iterations. Priority: this > SubAgentConfig.maxIterations > default (10). */
        public Builder maxIterations(int maxIterations) {
            this.maxIterations = maxIterations;
            return this;
        }

        /**
         * Enable verbose console logging for this sub-agent.
         * Prefixes every line with {@code [subagent:<name>]} for visual distinction.
         */
        public Builder verbose(boolean enabled) {
            if (enabled) {
                String agentName = config.getName();
                String prefix = "[subagent:" + agentName + "] ";
                this.onLlm         = msg -> System.out.println(prefix + "LLM input:\n" + msg);
                this.onToolCall    = tc  -> System.out.println(prefix + "ToolCall: " + tc);
                this.onObservation = obs -> System.out.println(prefix + "Observation: " + obs);
            } else {
                this.onLlm = null;
                this.onToolCall = null;
                this.onObservation = null;
            }
            return this;
        }

        public Builder onLlm(Consumer<String> consumer) {
            this.onLlm = consumer;
            return this;
        }

        public Builder onToolCall(Consumer<String> consumer) {
            this.onToolCall = consumer;
            return this;
        }

        public Builder onObservation(Consumer<String> consumer) {
            this.onObservation = consumer;
            return this;
        }

        public SubAgent build() {
            // llm required unless: model=inherit (injected later) OR model is named (factory resolves later)
            boolean llmWillBeProvided = config.isInheritModel()
                    || (config.getModel() != null && !config.getModel().isBlank())
                    || llmFactory != null;
            if (llm == null && !llmWillBeProvided) {
                throw new IllegalStateException(
                        "llm must be set for sub-agent '" + config.getName() + "'. " +
                        "Alternatively set model=inherit or model=<name> + llmFactory.");
            }
            int resolvedMaxIter = maxIterations != null ? maxIterations
                    : (config.getMaxIterations() != null ? config.getMaxIterations() : DEFAULT_MAX_ITERATIONS);
            return new SubAgent(config, chainActor, llm, llmFactory, ownTools, callableSkills,
                    resolvedMaxIter, onLlm, onToolCall, onObservation);
        }
    }
}
