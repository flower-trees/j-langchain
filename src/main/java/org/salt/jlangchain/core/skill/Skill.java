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

package org.salt.jlangchain.core.skill;

import lombok.extern.slf4j.Slf4j;
import org.salt.jlangchain.core.ChainActor;
import org.salt.jlangchain.core.agent.AgentTokenUsageEvent;
import org.salt.jlangchain.core.agent.McpAgentExecutor;
import org.salt.jlangchain.core.llm.BaseChatModel;
import org.salt.jlangchain.core.subagent.SubAgent;
import org.salt.jlangchain.core.subagent.SubAgentConfig;
import org.salt.jlangchain.rag.tools.Tool;

import org.salt.function.flow.context.ContextBus;
import org.salt.jlangchain.core.common.CallInfo;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * A skill is a self-contained, reusable agent unit that wraps a
 * {@link McpAgentExecutor} driven by a SKILL.md workflow.
 *
 * <p>From the master agent's perspective, a skill is just a {@link Tool}.
 * Internally it runs a full Function Calling loop guided by the system prompt
 * loaded from {@link SkillConfig} (field: systemPrompt).
 *
 * <p>Tools available to the internal sub-agent come from three sources:
 * <ol>
 *   <li>Script tools — auto-converted from {@link SkillConfig} (field: scripts)</li>
 *   <li>Own tools — explicitly added via {@link Builder#tools(Tool...)}</li>
 *   <li>Parent tools — injected by the master agent builder based on
 *       {@link SkillConfig} (field: allowedTools)</li>
 * </ol>
 *
 * <p>Observability:
 * <ul>
 *   <li>{@link Builder#verbose(boolean)} — auto-wires console logging with skill-name prefix</li>
 *   <li>{@link Builder#onToolCall}, {@link Builder#onObservation}, {@link Builder#onLlm}
 *       — fine-grained callbacks for production monitoring</li>
 * </ul>
 */
@Slf4j
public class Skill {

    private final SkillConfig config;
    private final ChainActor chainActor;
    private final BaseChatModel llm;
    private final List<Tool> ownTools;
    private final int maxIterations;
    private final boolean verbose;
    private final Consumer<String> onLlm;
    private final Consumer<String> onToolCall;
    private final Consumer<String> onObservation;
    private final Consumer<AgentTokenUsageEvent> onTokenUsage;
    private List<Tool> parentTools = new ArrayList<>();
    private volatile McpAgentExecutor executor;

    private Skill(SkillConfig config, ChainActor chainActor, BaseChatModel llm,
                  List<Tool> ownTools, int maxIterations, boolean verbose,
                  Consumer<String> onLlm, Consumer<String> onToolCall, Consumer<String> onObservation,
                  Consumer<AgentTokenUsageEvent> onTokenUsage) {
        this.config = config;
        this.chainActor = chainActor;
        this.llm = llm;
        this.ownTools = new ArrayList<>(ownTools);
        this.maxIterations = maxIterations;
        this.verbose = verbose;
        this.onLlm = onLlm;
        this.onToolCall = onToolCall;
        this.onObservation = onObservation;
        this.onTokenUsage = onTokenUsage;
    }

    public static Builder from(SkillConfig config, ChainActor chainActor) {
        return new Builder(config, chainActor);
    }

    // ── metadata (exposed to master agent builder) ───────────────────────────

    public String getName() {
        return config.getName();
    }

    public String getDescription() {
        return config.getDescription();
    }

    public List<String> getAllowedTools() {
        return config.getAllowedTools() != null ? config.getAllowedTools() : List.of();
    }

    // ── tool injection (called by McpAgentExecutor.Builder) ──────────────────

    public void injectParentTools(List<Tool> tools) {
        this.parentTools = new ArrayList<>(tools);
        this.executor = null;
    }

    // ── public API ───────────────────────────────────────────────────────────

    /** Wraps this skill as a Tool for use in a master agent's tool registry. */
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
        // Propagate parent's stop signal so a master stop cascades into this skill
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

        log.debug("Building internal executor for skill '{}' with {} tool(s): {}",
                getName(), allTools.size(), allTools.stream().map(Tool::getName).toList());

        var builder = McpAgentExecutor.builder(chainActor)
                .llm(llm)
                .systemPrompt(buildSystemPrompt())
                .tools(allTools)
                .maxIterations(maxIterations);

        // Register embedded sub-agents from agents/ directory
        if (config.getAgents() != null) {
            for (SubAgentConfig agentConfig : config.getAgents()) {
                var subAgentBuilder = SubAgent.from(agentConfig, chainActor).llm(llm);
                if (verbose) {
                    // verbose 模式：生成独立子前缀 [skill:travel_planner>budget_advisor]
                    String subPrefix = "[skill:" + config.getName() + ">" + agentConfig.getName() + "] ";
                    subAgentBuilder
                        .onLlm(msg -> System.out.println(subPrefix + "LLM input:\n" + msg))
                        .onToolCall(tc  -> System.out.println(subPrefix + "ToolCall: " + tc))
                        .onObservation(obs -> System.out.println(subPrefix + "Observation: " + obs));
                } else {
                    // 自定义回调：直接透传
                    if (onLlm != null)          subAgentBuilder.onLlm(onLlm);
                    if (onToolCall != null)      subAgentBuilder.onToolCall(onToolCall);
                    if (onObservation != null)   subAgentBuilder.onObservation(onObservation);
                }
                builder.subAgent(subAgentBuilder.build());
            }
        }

        if (onLlm != null)          builder.onLlm(onLlm);
        if (onToolCall != null)     builder.onToolCall(onToolCall);
        if (onObservation != null)  builder.onObservation(onObservation);
        if (onTokenUsage != null)   builder.onTokenUsage(onTokenUsage);

        return builder.build();
    }

    private List<Tool> collectTools() {
        List<Tool> all = new ArrayList<>();
        if (config.getScripts() != null) {
            config.getScripts().stream().map(ScriptTool::from).forEach(all::add);
        }
        all.addAll(ownTools);
        all.addAll(parentTools);
        return all;
    }

    private String buildSystemPrompt() {
        StringBuilder sb = new StringBuilder();
        if (config.getSystemPrompt() != null && !config.getSystemPrompt().isBlank()) {
            sb.append(config.getSystemPrompt());
        }
        if (config.getReferences() != null && !config.getReferences().isEmpty()) {
            sb.append("\n\n---\n\n");
            sb.append(String.join("\n\n---\n\n", config.getReferences()));
        }
        return sb.toString();
    }

    // ── builder ──────────────────────────────────────────────────────────────

    public static class Builder {

        private static final int DEFAULT_MAX_ITERATIONS = 10;

        private final SkillConfig config;
        private final ChainActor chainActor;
        private BaseChatModel llm;
        private final List<Tool> ownTools = new ArrayList<>();
        private Integer maxIterations;
        private boolean verbose = false;
        private Consumer<String> onLlm;
        private Consumer<String> onToolCall;
        private Consumer<String> onObservation;
        private Consumer<AgentTokenUsageEvent> onTokenUsage;

        private Builder(SkillConfig config, ChainActor chainActor) {
            this.config = config;
            this.chainActor = chainActor;
        }

        public Builder llm(BaseChatModel llm) {
            this.llm = llm;
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

        /** Override max iterations. Priority: this > SkillConfig.maxIterations > default (10). */
        public Builder maxIterations(int maxIterations) {
            this.maxIterations = maxIterations;
            return this;
        }

        /**
         * Enable verbose console logging for the internal sub-agent.
         * Prefixes every line with {@code [skill:<name>]} so master and skill
         * events are visually distinct.
         *
         * <pre>
         * [master]              ToolCall: travel_planner {...}
         *   [skill:travel_planner] ToolCall: get_weather {"city":"成都"}
         *   [skill:travel_planner] Observation: 成都：多云，18~26°C
         * [master]              Observation: （skill 最终返回）
         * </pre>
         */
        public Builder verbose(boolean enabled) {
            this.verbose = enabled;
            if (enabled) {
                String skillName = config.getName();
                String prefix = "[skill:" + skillName + "] ";
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

        /** Fine-grained callback: called with the full prompt before each LLM invocation. */
        public Builder onLlm(Consumer<String> consumer) {
            this.onLlm = consumer;
            return this;
        }

        /** Fine-grained callback: called with "{toolName} {argsJson}" on each tool call. */
        public Builder onToolCall(Consumer<String> consumer) {
            this.onToolCall = consumer;
            return this;
        }

        /** Fine-grained callback: called with the tool result string after each tool call. */
        public Builder onObservation(Consumer<String> consumer) {
            this.onObservation = consumer;
            return this;
        }

        public Builder onTokenUsage(Consumer<AgentTokenUsageEvent> consumer) {
            this.onTokenUsage = consumer;
            return this;
        }

        public Skill build() {
            if (llm == null) {
                throw new IllegalStateException("llm must be set for skill: " + config.getName());
            }
            int resolvedMaxIter = maxIterations != null ? maxIterations
                    : (config.getMaxIterations() != null ? config.getMaxIterations() : DEFAULT_MAX_ITERATIONS);
            return new Skill(config, chainActor, llm, ownTools, resolvedMaxIter, verbose,
                    onLlm, onToolCall, onObservation, onTokenUsage);
        }
    }
}
