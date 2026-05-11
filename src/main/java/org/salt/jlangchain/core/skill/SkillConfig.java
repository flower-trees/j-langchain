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

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.salt.jlangchain.core.subagent.SubAgentConfig;

import java.util.List;

/**
 * Pure data class representing a skill's full configuration.
 * Can be loaded from classpath (SKILL.md), database, or constructed in code.
 *
 * <p>Maps to the Claude Code SKILL.md format:
 * <ul>
 *   <li>frontmatter fields → name, description, allowedTools</li>
 *   <li>SKILL.md body → systemPrompt</li>
 *   <li>references/*.md → references (pre-loaded text)</li>
 *   <li>scripts/* → scripts (source code, written to temp files at runtime)</li>
 * </ul>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SkillConfig {

    /** Skill identifier, used as the tool name exposed to the master LLM. */
    private String name;

    /** Skill description shown to the master LLM for routing decisions.
     *  Should include TRIGGER / SKIP conditions. */
    private String description;

    /** Whitelist of parent agent tool names this skill is allowed to borrow. */
    private List<String> allowedTools;

    /** Workflow instructions for the internal sub-agent (SKILL.md body). */
    private String systemPrompt;

    /** Pre-loaded content from references/*.md, injected into the system prompt. */
    private List<String> references;

    /** Script definitions from scripts/*, converted to Tools at runtime. */
    private List<ScriptDef> scripts;

    /**
     * Embedded sub-agents from agents/*.md, registered as callable tools in the
     * skill's internal executor. Each agent's body becomes its systemPrompt;
     * name is derived from the filename.
     */
    private List<SubAgentConfig> agents;

    /** Max ReAct/FC iterations for the internal sub-agent. Null means use framework default (10). */
    private Integer maxIterations;
}
