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

package org.salt.jlangchain.core.skill.loader;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.salt.jlangchain.core.skill.ReferenceDoc;
import org.salt.jlangchain.core.skill.ReferencesMode;
import org.salt.jlangchain.core.skill.SkillConfig;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

/**
 * Covers references-mode (INLINE vs LAZY) and license/metadata passthrough at the loader level.
 */
public class FileSystemSkillConfigLoaderReferencesTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    @Test
    public void defaultsToInline_whenReferencesModeOmitted() throws IOException {
        Path dir = writeSkill("""
                ---
                name: demo
                description: demo skill
                ---

                Body text.
                """, "notes.md", "# Notes\nSome domain knowledge.");

        SkillConfig config = FileSystemSkillConfigLoader.fromPath(dir);

        assertEquals(ReferencesMode.INLINE, config.getReferencesMode());
        assertEquals(1, config.getReferences().size());
        ReferenceDoc ref = config.getReferences().get(0);
        assertEquals("notes.md", ref.filename());
        assertTrue(ref.content().contains("Some domain knowledge."));
        assertEquals("Notes", ref.summary());
    }

    @Test
    public void parsesLazyReferencesMode() throws IOException {
        Path dir = writeSkill("""
                ---
                name: demo
                description: demo skill
                references-mode: lazy
                ---

                Body text.
                """, "notes.md", "Plain first line summary.\nmore content");

        SkillConfig config = FileSystemSkillConfigLoader.fromPath(dir);

        assertEquals(ReferencesMode.LAZY, config.getReferencesMode());
        assertEquals("Plain first line summary.", config.getReferences().get(0).summary());
    }

    @Test
    public void parsesLicenseAndMetadata() throws IOException {
        Path dir = writeSkill("""
                ---
                name: demo
                description: demo skill
                license: Apache-2.0
                metadata:
                  author: someone
                  version: 2
                ---

                Body text.
                """, null, null);

        SkillConfig config = FileSystemSkillConfigLoader.fromPath(dir);

        assertEquals("Apache-2.0", config.getLicense());
        assertNotNull(config.getMetadata());
        assertEquals("someone", config.getMetadata().get("author"));
    }

    @Test
    public void unknownFrontmatterFields_areIgnoredWithoutError() throws IOException {
        Path dir = writeSkill("""
                ---
                name: demo
                description: demo skill
                some-future-field: whatever
                ---

                Body text.
                """, null, null);

        SkillConfig config = FileSystemSkillConfigLoader.fromPath(dir);

        assertEquals("demo", config.getName());
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private Path writeSkill(String skillMd, String referenceFilename, String referenceContent) throws IOException {
        Path dir = tmp.newFolder("skill").toPath();
        Files.writeString(dir.resolve("SKILL.md"), skillMd);
        if (referenceFilename != null) {
            Path refDir = Files.createDirectories(dir.resolve("references"));
            Files.writeString(refDir.resolve(referenceFilename), referenceContent);
        }
        return dir;
    }
}
