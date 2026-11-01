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
import org.junit.Test;
import org.junit.runner.RunWith;
import org.salt.jlangchain.TestApplication;
import org.salt.jlangchain.core.ChainActor;
import org.salt.jlangchain.core.llm.aliyun.ChatAliyun;
import org.salt.jlangchain.core.skill.loader.FileSystemSkillConfigLoader;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.junit4.SpringRunner;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.Assert.*;

/**
 * Regression test for the real bug report: running Claude Code's real "skill-creator" plugin
 * (no {@code allowed-tools}, a multi-file {@code scripts/} Python package) used to get stuck
 * in an unproductive loop — the model, with zero filesystem tools, tried to misuse the
 * ScriptTool-wrapped {@code utils}/{@code __init__} as a generic shell, and even once the
 * ScriptTool in-place-execution fix landed, it still had no way to create the skill directory
 * its own scripts needed to operate on.
 *
 * <p>This drives the real plugin end to end through Claude-compatible mode (scoped filesystem
 * tools, no bash) and asserts it can actually create, validate, and package a new skill —
 * and that it never calls {@code utils}/{@code __init__} (both are excluded from the tool set
 * entirely now, per {@link FileSystemSkillConfigLoaderScriptsTest}, so this also verifies that
 * exclusion holds for the real plugin, not just synthetic fixtures).
 *
 * <p>Needs the plugin installed locally and a reachable Aliyun/DashScope model — skipped
 * otherwise, same convention as Article30ClaudeCodeSkillCompat.
 */
@RunWith(SpringRunner.class)
@SpringBootTest(classes = TestApplication.class)
@SpringBootConfiguration
public class SkillCreatorClaudeCompatRegressionTest {

    private static final Path SKILL_DIR = Path.of(System.getProperty("user.home"),
            ".claude", "plugins", "marketplaces", "claude-plugins-official",
            "plugins", "skill-creator", "skills", "skill-creator");

    @Autowired
    private ChainActor chainActor;

    @Test
    public void skillCreator_createsValidatesAndPackagesANewSkill_withoutTouchingUtilsOrInit() throws IOException {
        Assume.assumeTrue("skip: skill-creator plugin not installed on this machine (" + SKILL_DIR + ")",
                Files.isDirectory(SKILL_DIR));

        SkillConfig config = FileSystemSkillConfigLoader.fromPath(SKILL_DIR);
        assertTrue("loader must default real plugin dirs to claude-compat mode", config.isClaudeCompatMode());
        assertTrue("allowed-tools must be empty for a real Claude Code skill",
                config.getAllowedTools() == null || config.getAllowedTools().isEmpty());

        List<String> toolCallNames = new CopyOnWriteArrayList<>();
        List<String> toolCallLines = new CopyOnWriteArrayList<>();
        List<String> observationLines = new CopyOnWriteArrayList<>();

        Skill skill = Skill.from(config, chainActor)
                .llm(ChatAliyun.builder().model("qwen-plus").temperature(0f).build())
                .maxIterations(15)
                .onToolCall(tc -> {
                    toolCallLines.add(tc);
                    toolCallNames.add(tc.split("\\s+", 2)[0]);
                })
                .onObservation(observationLines::add)
                .build();

        String result;
        try {
            result = skill.invoke("""
                    Create a brand new skill in your sandboxed workspace, following exactly these steps:
                    1. create_directory "ping-pong"
                    2. write_file "ping-pong/SKILL.md" with valid frontmatter:
                       ---
                       name: ping-pong
                       description: Replies "pong" to any message containing "ping". Trivial demo skill for a regression test.
                       ---
                       When the user says ping, reply pong.
                    3. Run quick_validate on the absolute path of the ping-pong directory you created (from step 1's result).
                    4. Run package_skill on that same absolute path.
                    5. Report whether validation and packaging both succeeded.
                    Do not explore the filesystem beyond what these steps need.
                    """);
        } catch (Exception e) {
            fail("skill-creator should complete within the iteration budget, but threw: " + describe(e));
            return;
        }

        System.out.println("\n========== skill-creator Claude-compat regression result ==========");
        System.out.println(result);
        System.out.println("Tool calls: " + toolCallNames);
        System.out.println("=====================================================================\n");

        assertFalse("result must not be blank", result == null || result.isBlank());

        assertFalse("utils must never be called (excluded from the tool set entirely)",
                toolCallNames.contains("utils"));
        assertFalse("__init__ must never be called (excluded from the tool set entirely)",
                toolCallNames.contains("__init__"));

        assertTrue("must have used the scoped filesystem tools to create the skill dir",
                toolCallNames.contains("create_directory"));
        assertTrue("must have written SKILL.md via the scoped filesystem tools",
                toolCallNames.contains("write_file"));

        boolean validatedOk = observationLines.stream()
                .anyMatch(o -> o.contains("Skill is valid") || o.contains("valid"));
        boolean packagedOk = observationLines.stream()
                .anyMatch(o -> o.contains("Successfully packaged") || o.contains("successfully packaged"));
        assertTrue("quick_validate must have reported success at some point:\n" + String.join("\n", observationLines),
                validatedOk);
        assertTrue("package_skill must have reported success at some point:\n" + String.join("\n", observationLines),
                packagedOk);
    }

    private String describe(Exception e) {
        List<String> parts = new ArrayList<>();
        for (Throwable t = e; t != null; t = t.getCause()) {
            parts.add(t.getClass().getSimpleName() + ": " + t.getMessage());
        }
        return String.join(" <- ", parts);
    }
}
