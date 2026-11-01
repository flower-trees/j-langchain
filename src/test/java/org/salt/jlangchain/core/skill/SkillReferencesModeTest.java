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
import org.salt.jlangchain.core.ChainActor;
import org.salt.jlangchain.core.llm.BaseChatModel;
import org.salt.jlangchain.ai.chat.strategy.AiChatActuator;
import org.salt.jlangchain.ai.common.param.AiChatInput;
import org.salt.jlangchain.rag.tools.Tool;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.Assert.*;

/**
 * Verifies references-mode behavior (INLINE vs LAZY handling of {@code references/*.md}) without
 * needing a real LLM or executor — {@link Skill#buildSystemPrompt()} / {@link Skill#collectTools()}
 * only read {@link SkillConfig}, so a stub {@link BaseChatModel} is enough to satisfy
 * {@link Skill.Builder#build()}.
 */
public class SkillReferencesModeTest {

    private static final BaseChatModel STUB_LLM = new BaseChatModel() {
        @Override public void otherInformation(AiChatInput aiChatInput) { }
        @Override public Class<? extends AiChatActuator> getActuator() { return null; }
        @Override public BaseChatModel copy() { return this; }
    };

    private Skill buildSkill(SkillConfig config) {
        return Skill.from(config, new ChainActor(null)).llm(STUB_LLM).build();
    }

    @Test
    public void inlineMode_isDefault_andConcatenatesFullContent() {
        SkillConfig config = SkillConfig.builder()
                .name("t")
                .description("d")
                .systemPrompt("BODY")
                .references(List.of(
                        new ReferenceDoc("a.md", "FULL-CONTENT-A", "summary a"),
                        new ReferenceDoc("b.md", "FULL-CONTENT-B", "summary b")
                ))
                .build();

        assertEquals(ReferencesMode.INLINE, config.getReferencesMode());

        Skill skill = buildSkill(config);
        String prompt = skill.buildSystemPrompt();

        assertTrue(prompt.contains("BODY"));
        assertTrue(prompt.contains("FULL-CONTENT-A"));
        assertTrue(prompt.contains("FULL-CONTENT-B"));
        assertFalse(findTool(skill, "read_reference").isPresent());
    }

    @Test
    public void lazyMode_addsManifestOnly_andRegistersReadReferenceTool() {
        SkillConfig config = SkillConfig.builder()
                .name("t")
                .description("d")
                .systemPrompt("BODY")
                .referencesMode(ReferencesMode.LAZY)
                .references(List.of(
                        new ReferenceDoc("a.md", "FULL-CONTENT-A", "summary a"),
                        new ReferenceDoc("b.md", "FULL-CONTENT-B", "summary b")
                ))
                .build();

        Skill skill = buildSkill(config);
        String prompt = skill.buildSystemPrompt();

        assertTrue(prompt.contains("BODY"));
        assertTrue(prompt.contains("a.md"));
        assertTrue(prompt.contains("summary a"));
        assertTrue(prompt.contains("b.md"));
        // full content must NOT be inlined in lazy mode
        assertFalse(prompt.contains("FULL-CONTENT-A"));
        assertFalse(prompt.contains("FULL-CONTENT-B"));

        Optional<Tool> readReference = findTool(skill, "read_reference");
        assertTrue(readReference.isPresent());

        Object result = readReference.get().invoke(Map.of("file", "a.md"));
        assertEquals("FULL-CONTENT-A", result);

        Object missing = readReference.get().invoke(Map.of("file", "missing.md"));
        assertTrue(missing.toString().startsWith("Error:"));
    }

    @Test
    public void lazyMode_withNoReferences_addsNoManifestAndNoTool() {
        SkillConfig config = SkillConfig.builder()
                .name("t")
                .description("d")
                .systemPrompt("BODY")
                .referencesMode(ReferencesMode.LAZY)
                .build();

        Skill skill = buildSkill(config);
        String prompt = skill.buildSystemPrompt();

        assertEquals("BODY", prompt);
        assertFalse(findTool(skill, "read_reference").isPresent());
    }

    private Optional<Tool> findTool(Skill skill, String name) {
        return skill.collectTools().stream().filter(t -> name.equals(t.getName())).findFirst();
    }
}
