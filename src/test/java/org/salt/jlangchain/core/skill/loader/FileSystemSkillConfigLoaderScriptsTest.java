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
import org.salt.jlangchain.core.skill.ScriptDef;
import org.salt.jlangchain.core.skill.SkillConfig;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.Assert.*;

/**
 * Covers two behaviors added to fix the skill-creator repro from
 * docs discussion: library-only Python files (no {@code __main__} guard, e.g. {@code utils.py},
 * {@code __init__.py}) must not become callable tools, and a real filesystem-loaded skill must
 * default to Claude-compatible mode.
 */
public class FileSystemSkillConfigLoaderScriptsTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    @Test
    public void loadsRealEntrypointScripts_butSkipsLibraryOnlyPythonFiles() throws IOException {
        Path dir = writeSkillMd();
        Path scripts = Files.createDirectories(dir.resolve("scripts"));
        Files.writeString(scripts.resolve("__init__.py"), "");
        Files.writeString(scripts.resolve("utils.py"), "def helper():\n    return 1\n");
        Files.writeString(scripts.resolve("run.py"), """
                import sys
                if __name__ == "__main__":
                    print("ran")
                """);

        SkillConfig config = FileSystemSkillConfigLoader.fromPath(dir);

        assertEquals(List.of("run"), config.getScripts().stream().map(ScriptDef::getName).toList());
    }

    @Test
    public void nonPythonScripts_areAlwaysTreatedAsEntrypoints() throws IOException {
        Path dir = writeSkillMd();
        Path scripts = Files.createDirectories(dir.resolve("scripts"));
        Files.writeString(scripts.resolve("build.sh"), "#!/bin/bash\necho hi\n");

        SkillConfig config = FileSystemSkillConfigLoader.fromPath(dir);

        assertEquals(List.of("build"), config.getScripts().stream().map(ScriptDef::getName).toList());
    }

    @Test
    public void filesystemLoadedSkill_defaultsToClaudeCompatMode() throws IOException {
        SkillConfig config = FileSystemSkillConfigLoader.fromPath(writeSkillMd());

        assertTrue(config.isClaudeCompatMode());
    }

    // ── missing "---" frontmatter fences → blank name falls back to directory name ──

    @Test
    public void skillMdWithoutFrontmatterFences_fallsBackNameToDirectoryName() throws IOException {
        // Reproduces the real skill-creator output that motivated this fallback: frontmatter
        // fields written as plain text with no "---" delimiters. Without the fallback this
        // produces name="", which downstream becomes capabilityId "<pluginId>." (trailing dot,
        // blank short name) — registered but unreachable by /skills or short-name dispatch.
        Path dir = tmp.newFolder("tang-shi").toPath();
        Files.writeString(dir.resolve("SKILL.md"), """
                name: tang-shi
                description: Compose Tang-style poetry.

                ## Body
                No frontmatter fences above — this whole file is parsed as one blob.
                """);

        SkillConfig config = FileSystemSkillConfigLoader.fromPath(dir);

        assertEquals("tang-shi", config.getName());
    }

    @Test
    public void skillMdWithProperFrontmatter_nameIsUnaffectedByDirectoryName() throws IOException {
        Path dir = tmp.newFolder("some-other-dir-name").toPath();
        Files.writeString(dir.resolve("SKILL.md"), """
                ---
                name: real-name
                description: d
                ---
                Body.
                """);

        SkillConfig config = FileSystemSkillConfigLoader.fromPath(dir);

        assertEquals("real-name", config.getName());
    }

    private Path writeSkillMd() throws IOException {
        Path dir = tmp.newFolder("skill").toPath();
        Files.writeString(dir.resolve("SKILL.md"), """
                ---
                name: demo
                description: demo skill
                ---
                Body.
                """);
        return dir;
    }
}
