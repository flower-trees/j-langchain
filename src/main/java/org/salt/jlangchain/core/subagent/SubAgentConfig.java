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

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.salt.jlangchain.core.skill.SkillConfig;

import java.util.List;

/**
 * Pure data class representing a sub-agent's full configuration.
 * Can be loaded from classpath (AGENT.md), database, or constructed in code.
 *
 * <p>Maps to the AGENT.md format:
 * <ul>
 *   <li>frontmatter fields → name, description, skills (dirs), max-iterations</li>
 *   <li>AGENT.md body → systemPrompt</li>
 *   <li>skills → pre-loaded {@link SkillConfig} objects, injected as knowledge into systemPrompt</li>
 * </ul>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SubAgentConfig {

    /** Sub-agent identifier, used as the tool name exposed to the master LLM. */
    private String name;

    /** Sub-agent description shown to the master LLM for routing decisions. */
    private String description;

    /** System prompt for the internal executor (AGENT.md body). */
    private String systemPrompt;

    /**
     * Skill knowledge to bake into this sub-agent's system prompt.
     * Each skill's systemPrompt and references are appended verbatim —
     * the sub-agent "knows" the skill workflows without calling them as tools.
     */
    private List<SkillConfig> skills;

    /** Max function-calling iterations for the internal executor. Null means use default (10). */
    private Integer maxIterations;
}
