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

import org.junit.Assume;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.salt.jlangchain.core.skill.loader.FileSystemSkillConfigLoader;
import org.salt.jlangchain.rag.tools.Tool;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.Assert.*;

/**
 * Reproduces the failure mode observed running Claude Code's real "skill-creator" plugin
 * through regnexe-cli's {@code skills.extra_dirs}: a multi-file {@code scripts/} Python
 * package with relative imports (a sibling {@code __init__.py}) and scripts that expect to
 * run with the skill's own directory as CWD (e.g. to find {@code SKILL.md}, {@code
 * references/}). Both broke because {@link ScriptTool} used to extract every script into its
 * own isolated temp directory, discarding sibling files and CWD.
 *
 * <p>Requires a {@code python} interpreter on PATH; skipped otherwise.
 */
public class ScriptToolInPlaceExecutionTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    @Test
    public void relativeImportAcrossSiblingScripts_resolvesWhenRunInPlace() throws IOException {
        assumePythonAvailable();

        Path skillDir = writeSkillWithPackageScripts();
        SkillConfig config = FileSystemSkillConfigLoader.fromPath(skillDir);
        ScriptDef mainDef = config.getScripts().stream()
                .filter(s -> s.getName().equals("main")).findFirst().orElseThrow();

        assertNotNull("loader must populate sourcePath for filesystem-loaded scripts", mainDef.getSourcePath());
        assertNotNull("loader must populate workDir for filesystem-loaded scripts", mainDef.getWorkDir());

        Tool tool = ScriptTool.from(mainDef);
        Object result = tool.getFunc().apply(Map.of("args", ""));

        // Before the fix: "ModuleNotFoundError: No module named 'scripts'"
        assertEquals("shout:HELLO", result.toString().trim());
    }

    @Test
    public void relativeFileLookup_resolvesAgainstSkillRootNotTempDir() throws IOException {
        assumePythonAvailable();

        Path skillDir = writeSkillThatReadsOwnSkillMd();
        SkillConfig config = FileSystemSkillConfigLoader.fromPath(skillDir);
        ScriptDef checkDef = config.getScripts().get(0);

        Tool tool = ScriptTool.from(checkDef);
        Object result = tool.getFunc().apply(Map.of("args", ""));

        // Before the fix: "SKILL.md not found" (script ran from an unrelated temp CWD)
        assertEquals("found", result.toString().trim());
    }

    @Test
    public void classpathStyleScriptDef_withoutSourcePath_stillRunsFromTempCopy() {
        assumePythonAvailable();

        // No sourcePath/workDir set — same as ClasspathSkillConfigLoader/code-first configs.
        ScriptDef def = ScriptDef.builder()
                .name("standalone").type("py")
                .content("print('temp-copy-still-works')\n")
                .build();

        Tool tool = ScriptTool.from(def);
        Object result = tool.getFunc().apply(Map.of("args", ""));

        assertEquals("temp-copy-still-works", result.toString().trim());
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private void assumePythonAvailable() {
        try {
            Process p = new ProcessBuilder("python", "--version").redirectErrorStream(true).start();
            Assume.assumeTrue("python not usable on this machine", p.waitFor() == 0);
        } catch (Exception e) {
            Assume.assumeNoException("python not found on PATH", e);
        }
    }

    /**
     * <pre>
     * {tmp}/skill/
     *   SKILL.md
     *   scripts/
     *     __init__.py
     *     utils.py    - def shout(s): return "shout:" + s.upper()
     *     main.py     - from scripts.utils import shout; if __name__ == "__main__": print(shout("hello"))
     * </pre>
     */
    private Path writeSkillWithPackageScripts() throws IOException {
        Path dir = tmp.newFolder("skill").toPath();
        Files.writeString(dir.resolve("SKILL.md"), """
                ---
                name: demo
                description: demo skill with a multi-file scripts/ package
                ---

                Body text.
                """);
        Path scripts = Files.createDirectories(dir.resolve("scripts"));
        Files.writeString(scripts.resolve("__init__.py"), "");
        Files.writeString(scripts.resolve("utils.py"), """
                def shout(s):
                    return "shout:" + s.upper()
                """);
        Files.writeString(scripts.resolve("main.py"), """
                from scripts.utils import shout

                if __name__ == "__main__":
                    print(shout("hello"))
                """);
        return dir;
    }

    /**
     * <pre>
     * {tmp}/skill/
     *   SKILL.md
     *   scripts/
     *     check.py    - if __name__ == "__main__": print("found" if os.path.exists("SKILL.md") else "SKILL.md not found")
     * </pre>
     */
    private Path writeSkillThatReadsOwnSkillMd() throws IOException {
        Path dir = tmp.newFolder("skill").toPath();
        Files.writeString(dir.resolve("SKILL.md"), """
                ---
                name: demo
                description: demo skill whose script looks up a sibling file by relative path
                ---

                Body text.
                """);
        Path scripts = Files.createDirectories(dir.resolve("scripts"));
        Files.writeString(scripts.resolve("check.py"), """
                import os

                if __name__ == "__main__":
                    print("found" if os.path.exists("SKILL.md") else "SKILL.md not found")
                """);
        return dir;
    }
}
