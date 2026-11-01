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

import org.junit.Test;
import org.salt.jlangchain.ai.chat.strategy.AiChatActuator;
import org.salt.jlangchain.ai.common.param.AiChatInput;
import org.salt.jlangchain.core.ChainActor;
import org.salt.jlangchain.core.llm.BaseChatModel;
import org.salt.jlangchain.rag.tools.Tool;

import java.util.List;

import static org.junit.Assert.*;

/**
 * Covers the "Claude-compatible" skill execution mode: whether a skill with no allowed-tools
 * gets the scoped workspace filesystem tools, per {@link SkillConfig#isClaudeCompatMode()} and
 * its {@link Skill.Builder#claudeCompatMode(boolean)} override. No LLM/executor needed —
 * {@link Skill#collectTools()} only reads config, same pattern as SkillReferencesModeTest.
 */
public class SkillClaudeCompatModeTest {

    private static final BaseChatModel STUB_LLM = new BaseChatModel() {
        @Override public void otherInformation(AiChatInput aiChatInput) { }
        @Override public Class<? extends AiChatActuator> getActuator() { return null; }
        @Override public BaseChatModel copy() { return this; }
    };

    private static final List<String> FS_TOOL_NAMES =
            List.of("create_directory", "write_file", "read_file", "list_directory", "file_exists");

    private Skill.Builder builder(SkillConfig config) {
        return Skill.from(config, new ChainActor(null)).llm(STUB_LLM);
    }

    @Test
    public void claudeCompatMode_withNoAllowedTools_addsScopedFsToolsButNotBash() {
        SkillConfig config = SkillConfig.builder()
                .name("t").description("d").systemPrompt("BODY")
                .claudeCompatMode(true)
                .build();

        List<String> names = toolNames(builder(config).build());

        assertTrue(names.containsAll(FS_TOOL_NAMES));
        assertFalse(names.contains("bash"));
    }

    @Test
    public void claudeCompatMode_withBashOptIn_alsoAddsScopedBash() {
        SkillConfig config = SkillConfig.builder()
                .name("t").description("d").systemPrompt("BODY")
                .claudeCompatMode(true)
                .build();

        List<String> names = toolNames(builder(config).claudeCompatBash(true).build());

        assertTrue(names.contains("bash"));
    }

    @Test
    public void claudeCompatMode_defaultsFalse_forCodeFirstConfigs() {
        SkillConfig config = SkillConfig.builder()
                .name("t").description("d").systemPrompt("BODY")
                .build();

        List<String> names = toolNames(builder(config).build());

        FS_TOOL_NAMES.forEach(name -> assertFalse(name + " must not be present", names.contains(name)));
    }

    @Test
    public void claudeCompatMode_withDeclaredAllowedTools_doesNotAddFsTools() {
        // Real Claude Code skills never declare allowed-tools, but a hand-authored skill that
        // does should keep using the existing parent-tool injection mechanism unchanged.
        SkillConfig config = SkillConfig.builder()
                .name("t").description("d").systemPrompt("BODY")
                .claudeCompatMode(true)
                .allowedTools(List.of("some_parent_tool"))
                .build();

        List<String> names = toolNames(builder(config).build());

        FS_TOOL_NAMES.forEach(name -> assertFalse(name + " must not be present", names.contains(name)));
    }

    @Test
    public void builderOverride_canForceModeOnDespiteConfigFalse() {
        SkillConfig config = SkillConfig.builder().name("t").description("d").systemPrompt("BODY").build();

        List<String> names = toolNames(builder(config).claudeCompatMode(true).build());

        assertTrue(names.containsAll(FS_TOOL_NAMES));
    }

    @Test
    public void builderOverride_canForceModeOffDespiteConfigTrue() {
        SkillConfig config = SkillConfig.builder().name("t").description("d").systemPrompt("BODY")
                .claudeCompatMode(true).build();

        List<String> names = toolNames(builder(config).claudeCompatMode(false).build());

        FS_TOOL_NAMES.forEach(name -> assertFalse(name + " must not be present", names.contains(name)));
    }

    @Test
    public void systemPrompt_mentionsWorkspaceRoot_whenModeActive() {
        SkillConfig config = SkillConfig.builder()
                .name("t").description("d").systemPrompt("BODY")
                .claudeCompatMode(true)
                .build();

        Skill skill = builder(config).build();
        skill.collectTools();  // resolves/creates the lazy workspace

        String prompt = skill.buildSystemPrompt();
        assertTrue(prompt.contains("sandboxed workspace root"));
        assertTrue(prompt.contains(skill.resolveWorkspace().toString()));
    }

    @Test
    public void systemPrompt_unchanged_whenModeInactive() {
        SkillConfig config = SkillConfig.builder()
                .name("t").description("d").systemPrompt("BODY")
                .build();

        String prompt = builder(config).build().buildSystemPrompt();

        assertEquals("BODY", prompt);
    }

    private List<String> toolNames(Skill skill) {
        return skill.collectTools().stream().map(Tool::getName).toList();
    }
}
