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
import org.salt.jlangchain.core.agent.McpAgentExecutor;
import org.salt.jlangchain.core.llm.BaseChatModel;
import org.salt.jlangchain.rag.tools.Tool;

import java.util.ArrayList;
import java.util.List;

/**
 * A skill is a self-contained, reusable agent unit that wraps a
 * {@link McpAgentExecutor} driven by a SKILL.md workflow.
 *
 * <p>From the master agent's perspective, a skill is just a {@link Tool}.
 * Internally it runs a full Function Calling loop guided by the system prompt
 * loaded from {@link SkillConfig#getSystemPrompt()}.
 *
 * <p>Tools available to the internal sub-agent come from three sources:
 * <ol>
 *   <li>Script tools — auto-converted from {@link SkillConfig#getScripts()}</li>
 *   <li>Own tools — explicitly added via {@link Builder#tools(Tool...)}</li>
 *   <li>Parent tools — injected by the master agent builder based on
 *       {@link SkillConfig#getAllowedTools()}</li>
 * </ol>
 *
 * <pre>{@code
 * SkillConfig config = ClasspathSkillConfigLoader.load("skills/flight-search");
 * Skill skill = Skill.from(config, chainActor).llm(llm).build();
 *
 * McpAgentExecutor master = McpAgentExecutor.builder(chainActor)
 *     .llm(masterLlm)
 *     .tools(dbTool, flightQueryTool)
 *     .skill(skill)
 *     .build();
 * }</pre>
 */
@Slf4j
public class Skill {

    private final SkillConfig config;
    private final ChainActor chainActor;
    private final BaseChatModel llm;
    private final List<Tool> ownTools;
    private List<Tool> parentTools = new ArrayList<>();
    private volatile McpAgentExecutor executor;

    private Skill(SkillConfig config, ChainActor chainActor, BaseChatModel llm, List<Tool> ownTools) {
        this.config = config;
        this.chainActor = chainActor;
        this.llm = llm;
        this.ownTools = new ArrayList<>(ownTools);
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
        this.executor = null; // reset so executor rebuilds with new tools
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
        return executor.invoke(input).getText();
    }

    // ── internal executor construction ───────────────────────────────────────

    private McpAgentExecutor buildExecutor() {
        List<Tool> allTools = collectTools();
        if (allTools.isEmpty()) {
            throw new IllegalStateException(
                    "Skill '" + getName() + "' has no tools. " +
                    "Provide scripts in SKILL.md, add via .tools(), or declare allowedTools.");
        }

        log.debug("Building internal executor for skill '{}' with {} tool(s): {}",
                getName(), allTools.size(), allTools.stream().map(Tool::getName).toList());

        return McpAgentExecutor.builder(chainActor)
                .llm(llm)
                .systemPrompt(buildSystemPrompt())
                .tools(allTools)
                .build();
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

        private final SkillConfig config;
        private final ChainActor chainActor;
        private BaseChatModel llm;
        private final List<Tool> ownTools = new ArrayList<>();

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

        public Skill build() {
            if (llm == null) {
                throw new IllegalStateException("llm must be set for skill: " + config.getName());
            }
            return new Skill(config, chainActor, llm, ownTools);
        }
    }
}
