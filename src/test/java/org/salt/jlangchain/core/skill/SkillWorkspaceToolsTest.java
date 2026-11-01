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
import org.salt.jlangchain.rag.tools.Tool;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

public class SkillWorkspaceToolsTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    @Test
    public void createWriteReadListExist_roundTripCorrectly() throws IOException {
        Path root = tmp.newFolder("workspace").toPath();
        List<Tool> tools = SkillWorkspaceTools.forRoot(root);

        Tool createDir = find(tools, "create_directory");
        Tool writeFile = find(tools, "write_file");
        Tool readFile = find(tools, "read_file");
        Tool listDir = find(tools, "list_directory");
        Tool exists = find(tools, "file_exists");

        assertEquals("false", exists.getFunc().apply(Map.of("path", "sub")).toString());

        Object createResult = createDir.getFunc().apply(Map.of("path", "sub/dir"));
        assertTrue(createResult.toString().startsWith("Created:"));
        // Returns an absolute path so the model can pass it straight into scripts that expect a
        // real filesystem path (e.g. skill-creator's quick_validate/package_skill).
        assertTrue(createResult.toString().contains(root.resolve("sub/dir").toString())
                || createResult.toString().contains(root.toRealPath().resolve("sub/dir").toString()));
        assertTrue(Files.isDirectory(root.resolve("sub/dir")));

        writeFile.getFunc().apply(Map.of("path", "sub/dir/SKILL.md", "content", "hello world"));
        assertEquals("hello world", readFile.getFunc().apply(Map.of("path", "sub/dir/SKILL.md")).toString());

        assertEquals("true", exists.getFunc().apply(Map.of("path", "sub/dir/SKILL.md")).toString());

        Object listing = listDir.getFunc().apply(Map.of("path", "sub/dir"));
        assertEquals("SKILL.md", listing.toString());
    }

    @Test
    public void readMissingFile_returnsErrorNotException() throws IOException {
        Path root = tmp.newFolder("workspace").toPath();
        Tool readFile = find(SkillWorkspaceTools.forRoot(root), "read_file");
        Object result = readFile.getFunc().apply(Map.of("path", "nope.txt"));
        assertTrue(result.toString().startsWith("Error:"));
    }

    @Test(expected = SecurityException.class)
    public void dotDotTraversal_isRejected() throws IOException {
        Path root = tmp.newFolder("workspace").toPath();
        SkillWorkspaceTools.resolve(root, "../escape.txt");
    }

    @Test(expected = SecurityException.class)
    public void absolutePathOutsideRoot_isRejected() throws IOException {
        Path root = tmp.newFolder("workspace").toPath();
        Path outside = tmp.newFolder("outside").toPath();
        SkillWorkspaceTools.resolve(root, outside.resolve("secret.txt").toString());
    }

    @Test
    public void symlinkEscapingRoot_isRejected() throws IOException {
        Path root = tmp.newFolder("workspace").toPath();
        Path outside = tmp.newFolder("outside").toPath();
        Path secretFile = outside.resolve("secret.txt");
        Files.writeString(secretFile, "secret");
        Path link = root.resolve("escape-link");
        try {
            Files.createSymbolicLink(link, secretFile);
        } catch (UnsupportedOperationException | IOException e) {
            Assume.assumeNoException("symlinks not supported/permitted on this machine", e);
        }
        try {
            SkillWorkspaceTools.resolve(root, "escape-link");
            fail("expected SecurityException");
        } catch (SecurityException expected) {
            // ok
        }
    }

    @Test
    public void toolFuncThrowsSecurityException_ratherThanSilentlySucceeding() throws IOException {
        Path root = tmp.newFolder("workspace").toPath();
        Tool readFile = find(SkillWorkspaceTools.forRoot(root), "read_file");
        try {
            readFile.getFunc().apply(Map.of("path", "../../etc/passwd"));
            fail("expected SecurityException");
        } catch (SecurityException expected) {
            // ok — McpAgentExecutor's generic tool-call catch turns this into a clear
            // "Tool execution error" observation for the model, no special-casing needed here.
        }
    }

    private Tool find(List<Tool> tools, String name) {
        return tools.stream().filter(t -> t.getName().equals(name)).findFirst()
                .orElseThrow(() -> new AssertionError("tool not found: " + name));
    }
}
