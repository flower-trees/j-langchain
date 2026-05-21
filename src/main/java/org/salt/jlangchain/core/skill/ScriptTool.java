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

import lombok.extern.slf4j.Slf4j;
import org.salt.jlangchain.rag.tools.Tool;
import org.salt.jlangchain.utils.JsonUtil;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.*;

/**
 * Converts a {@link ScriptDef} into an executable {@link Tool}.
 *
 * <p>The script source is written to a temp file; the tool executes it via
 * ProcessBuilder. Only stdout is captured — the script source never enters
 * any LLM context.
 *
 * <p>Built-in executors: py → python, sh → bash, js → node, rb → ruby.
 * Register custom executors via {@link #register(String, String...)}.
 */
@Slf4j
public class ScriptTool {

    private static final Map<String, String[]> EXECUTORS = new LinkedHashMap<>();

    static {
        EXECUTORS.put("py", new String[]{"python"});
        EXECUTORS.put("sh", new String[]{"bash"});
        EXECUTORS.put("js", new String[]{"node"});
        EXECUTORS.put("rb", new String[]{"ruby"});
        EXECUTORS.put("groovy", new String[]{"groovy"});
    }

    public static void register(String ext, String... command) {
        EXECUTORS.put(ext.toLowerCase(), command);
    }

    public static boolean supports(String ext) {
        return EXECUTORS.containsKey(ext.toLowerCase());
    }

    public static Tool from(ScriptDef def) {
        String[] executor = EXECUTORS.get(def.getType().toLowerCase());
        if (executor == null) {
            throw new IllegalArgumentException("Unsupported script type: " + def.getType());
        }
        Path tempScript = writeTempScript(def);

        return Tool.builder()
                .name(def.getName())
                .description("Execute script: " + def.getName())
                .params("args: String")
                .func(args -> execute(executor, tempScript, resolveArgs(args)))
                .build();
    }

    /**
     * Converts the tool func input (always a Map from McpAgentExecutor) to a command-line string.
     * - Single key "args": extract the value directly — script receives a plain string.
     * - Any other shape: serialize the whole map as JSON — script receives a JSON string.
     */
    @SuppressWarnings("unchecked")
    static String resolveArgs(Object args) {
        if (args == null) return "";
        if (args instanceof Map<?, ?> map) {
            if (map.size() == 1 && map.containsKey("args")) {
                Object val = map.get("args");
                return val != null ? val.toString() : "";
            }
            return JsonUtil.toJson(args);
        }
        return args.toString();
    }

    private static Path writeTempScript(ScriptDef def) {
        try {
            Path tempDir = Files.createTempDirectory("jlangchain-skill-scripts");
            Path scriptFile = tempDir.resolve(def.getName() + "." + def.getType());
            Files.writeString(scriptFile, def.getContent());
            try {
                Set<PosixFilePermission> perms = new HashSet<>(Files.getPosixFilePermissions(scriptFile));
                perms.add(PosixFilePermission.OWNER_EXECUTE);
                Files.setPosixFilePermissions(scriptFile, perms);
            } catch (UnsupportedOperationException ignored) {
                // Windows: POSIX permissions not supported, skip
            }
            return scriptFile;
        } catch (IOException e) {
            throw new RuntimeException("Failed to write temp script: " + def.getName(), e);
        }
    }

    private static String execute(String[] executor, Path scriptPath, String args) {
        try {
            List<String> cmd = new ArrayList<>(List.of(executor));
            cmd.add(scriptPath.toString());
            if (!args.isBlank()) {
                cmd.add(args);
            }
            Process process = new ProcessBuilder(cmd)
                    .redirectErrorStream(true)
                    .start();
            String output = new String(process.getInputStream().readAllBytes());
            int exitCode = process.waitFor();
            if (exitCode != 0) {
                log.warn("Script '{}' exited with code {}: {}", scriptPath.getFileName(), exitCode, output.trim());
            }
            return output.trim();
        } catch (Exception e) {
            log.error("Script execution failed: {}", scriptPath, e);
            return "Script error: " + e.getMessage();
        }
    }
}
