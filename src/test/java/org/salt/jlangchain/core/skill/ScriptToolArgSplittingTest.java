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

import java.util.List;

import static org.junit.Assert.*;

/**
 * Covers the bug from the skill-creator repro: a script's {@code args} string with multiple
 * flags/positional args (e.g. {@code generate_report.py}'s {@code input --skill-name X -o Y})
 * used to be passed as one single argv token instead of being split — argparse then saw one
 * garbage positional argument instead of the intended arguments, and the underlying script
 * crashed with a traceback.
 */
public class ScriptToolArgSplittingTest {

    @Test
    public void blankOrNullArgs_yieldNoTokens() {
        assertEquals(List.of(), ScriptTool.splitShellArgs(null));
        assertEquals(List.of(), ScriptTool.splitShellArgs(""));
        assertEquals(List.of(), ScriptTool.splitShellArgs("   "));
    }

    @Test
    public void singleToken_unchanged() {
        assertEquals(List.of("/tmp/skill-dir"), ScriptTool.splitShellArgs("/tmp/skill-dir"));
    }

    @Test
    public void multipleFlagsAndPositional_splitOnWhitespace() {
        assertEquals(
                List.of("/tmp/report.json", "--skill-name", "tang-poetry", "-o", "/tmp/out.html"),
                ScriptTool.splitShellArgs("/tmp/report.json --skill-name tang-poetry -o /tmp/out.html"));
    }

    @Test
    public void doubleQuotedValueWithSpaces_staysOneToken() {
        assertEquals(
                List.of("/tmp/report.json", "--skill-name", "tang poetry composer", "-o", "/tmp/out.html"),
                ScriptTool.splitShellArgs(
                        "/tmp/report.json --skill-name \"tang poetry composer\" -o /tmp/out.html"));
    }

    @Test
    public void singleQuotedValueWithSpaces_staysOneToken() {
        assertEquals(
                List.of("--title", "hello world"),
                ScriptTool.splitShellArgs("--title 'hello world'"));
    }

    @Test
    public void escapedQuoteInsideDoubleQuotes_isUnescaped() {
        assertEquals(
                List.of("say \"hi\""),
                ScriptTool.splitShellArgs("\"say \\\"hi\\\"\""));
    }

    @Test
    public void extraWhitespaceBetweenTokens_isCollapsed() {
        assertEquals(
                List.of("a", "b", "c"),
                ScriptTool.splitShellArgs("  a    b\tc  "));
    }

    @Test
    public void shellMetacharacters_areNotInterpreted_treatedAsLiteralText() {
        // No shell delegation — ';', '|', '$()', backticks are just characters, never executed.
        assertEquals(
                List.of("foo;", "rm", "-rf", "/", "|", "cat"),
                ScriptTool.splitShellArgs("foo; rm -rf / | cat"));
    }

    // ── end-to-end: exact failure pattern from the skill-creator repro ────────────────────

    @Test
    public void generateReportStyleInvocation_reproducesRealScriptArgparse() throws Exception {
        // Mirrors generate_report.py's real argparse spec: input (positional), -o/--output,
        // --skill-name. Verify the split list is exactly what argparse would expect — i.e.
        // the fix actually solves the traceback, not just "some token list".
        List<String> tokens = ScriptTool.splitShellArgs(
                "/tmp/benchmark.json --skill-name \"tang-poetry-composer\" -o /tmp/review.html");
        assertEquals(List.of("/tmp/benchmark.json", "--skill-name", "tang-poetry-composer",
                "-o", "/tmp/review.html"), tokens);

        // Actually run it through python's argv to prove it's not just token-shaped, but correct.
        ProcessBuilder pb = new ProcessBuilder(
                new java.util.ArrayList<>(List.of("python", "-c", "import sys; print(sys.argv[1:])")));
        pb.command().addAll(tokens);
        pb.redirectErrorStream(true);
        Process p = pb.start();
        String output = new String(p.getInputStream().readAllBytes()).trim();
        p.waitFor();
        assertEquals("['/tmp/benchmark.json', '--skill-name', 'tang-poetry-composer', '-o', '/tmp/review.html']",
                output);
    }
}
