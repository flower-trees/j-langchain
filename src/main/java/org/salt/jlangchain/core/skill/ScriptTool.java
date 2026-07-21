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
import java.util.regex.Pattern;

/**
 * Converts a {@link ScriptDef} into an executable {@link Tool}.
 *
 * <p>When {@link ScriptDef#setSourcePath} is set (filesystem-loaded scripts), the script
 * runs in place from its real location, with {@link ScriptDef#setWorkDir} as the process's
 * working directory — this keeps sibling files (other scripts, {@code __init__.py},
 * {@code references/}, {@code assets/}) reachable, which multi-file script packages with
 * relative imports depend on. Python scripts whose own directory is a package (has an
 * {@code __init__.py}) are invoked as {@code python -m <dotted.module.path>} rather than by
 * file path, so {@code from scripts.x import y}-style imports resolve — this matches how
 * Claude Code's own SKILL.md authors document script invocation (e.g. skill-creator's
 * {@code python -m scripts.package_skill <path>}).
 *
 * <p>Otherwise (classpath-loaded or code-first scripts, with no real directory to run from),
 * the script source is extracted to an isolated temp file and executed via ProcessBuilder,
 * as before. Only stdout is captured — the script source never enters any LLM context.
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

    /** {@code if __name__ == "__main__":} / {@code == '__main__':}, allowing whitespace variations. */
    private static final Pattern PYTHON_MAIN_GUARD =
            Pattern.compile("(?m)^\\s*if\\s+__name__\\s*==\\s*['\"]__main__['\"]\\s*:");

    /**
     * Whether a script defines a real, invocable entrypoint rather than being a pure library
     * module (e.g. a shared {@code utils.py} of helper functions, or an empty {@code
     * __init__.py} package marker). Only Python is checked — other supported languages (sh/js/
     * rb/groovy) have no equivalent "library-only file" convention, so every file of those
     * types is always considered runnable.
     *
     * <p>Registering a library-only Python file as a callable Tool is actively harmful: the
     * model sees a plausible-looking tool name with no way to tell it apart from a real command,
     * calls it with arbitrary args expecting shell-like behavior, and silently gets no output
     * (the file just defines functions and exits) — observed in practice with Claude Code's
     * skill-creator plugin, where the model repeatedly tried to use its {@code utils}/{@code
     * __init__} script-tools as a substitute for a missing generic shell tool.
     */
    public static boolean hasEntrypoint(String type, String content) {
        if (!"py".equalsIgnoreCase(type)) return true;
        return content != null && PYTHON_MAIN_GUARD.matcher(content).find();
    }

    public static Tool from(ScriptDef def) {
        String[] executor = EXECUTORS.get(def.getType().toLowerCase());
        if (executor == null) {
            throw new IllegalArgumentException("Unsupported script type: " + def.getType());
        }
        return (def.getSourcePath() != null && def.getWorkDir() != null)
                ? fromInPlace(def, executor)
                : fromTempCopy(def, executor);
    }

    private static Tool fromTempCopy(ScriptDef def, String[] executor) {
        Path tempScript = writeTempScript(def);
        return Tool.builder()
                .name(def.getName())
                .description("[script] Run the bundled '" + def.getName() + "' command-line script.")
                .params("args: String")
                .func(args -> run(commandFor(executor, tempScript, resolveArgs(args)), null))
                .build();
    }

    private static Tool fromInPlace(ScriptDef def, String[] executor) {
        Path scriptFile = Path.of(def.getSourcePath());
        Path cwd = Path.of(def.getWorkDir());
        Path initPy = scriptFile.getParent().resolve("__init__.py");
        boolean isPythonPackage = "py".equalsIgnoreCase(def.getType()) && Files.exists(initPy);

        String description = isPythonPackage
                ? "[script] Run the bundled command-line script '" + def.getName()
                    + "' (python module scripts." + def.getName() + "). Not a shell — args is that "
                    + "script's own CLI arguments (positional + --flags), space-separated, quote a value "
                    + "that itself contains spaces."
                : "[script] Run the bundled '" + def.getName() + "' command-line script.";

        return Tool.builder()
                .name(def.getName())
                .description(description)
                .params("args: String")
                .func(args -> {
                    String resolvedArgs = resolveArgs(args);
                    List<String> cmd = isPythonPackage
                            ? moduleCommand(executor, cwd, scriptFile, resolvedArgs)
                            : commandFor(executor, scriptFile, resolvedArgs);
                    return run(cmd, cwd);
                })
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

    private static List<String> commandFor(String[] executor, Path scriptPath, String args) {
        List<String> cmd = new ArrayList<>(List.of(executor));
        cmd.add(scriptPath.toString());
        cmd.addAll(splitShellArgs(args));
        return cmd;
    }

    /**
     * Builds a {@code python -m <dotted.module.path>} command. The module path is derived
     * from {@code scriptFile}'s location relative to {@code cwd} (e.g. {@code scripts/foo.py}
     * run from the skill root becomes module {@code scripts.foo}), which is what lets Python
     * resolve {@code from scripts.x import y}-style imports within the package.
     */
    private static List<String> moduleCommand(String[] executor, Path cwd, Path scriptFile, String args) {
        String modulePath = cwd.relativize(scriptFile).toString();
        String moduleName = modulePath.substring(0, modulePath.length() - 3)  // strip ".py"
                .replace(java.io.File.separatorChar, '.');
        List<String> cmd = new ArrayList<>(List.of(executor));
        cmd.add("-m");
        cmd.add(moduleName);
        cmd.addAll(splitShellArgs(args));
        return cmd;
    }

    /**
     * Splits a raw args string into shell-style argv tokens — whitespace-separated, with
     * single/double-quoted substrings kept as one token (quotes stripped, {@code \"} and
     * {@code \\} recognized inside double quotes). Lets a caller pass a real multi-argument
     * command line (e.g. {@code report.json --skill-name "my skill" -o out.html}) and have it
     * reach the script as separate argv entries — the shape every standard CLI parser
     * (argparse, etc.) expects — instead of one opaque blob glued into a single argv[1].
     *
     * <p>Deliberately does NOT run through a shell (no {@code bash -c}): shell metacharacters
     * ({@code ; | & $() `}) are treated as ordinary characters, not interpreted. The args value
     * comes from model output, so avoiding shell interpretation avoids command injection — this
     * only changes how the string is tokenized into argv entries, not what each entry can do.
     */
    static List<String> splitShellArgs(String args) {
        List<String> tokens = new ArrayList<>();
        if (args == null || args.isBlank()) return tokens;

        StringBuilder current = new StringBuilder();
        boolean inSingleQuote = false;
        boolean inDoubleQuote = false;
        boolean hasToken = false;

        for (int i = 0; i < args.length(); i++) {
            char c = args.charAt(i);
            if (inSingleQuote) {
                if (c == '\'') {
                    inSingleQuote = false;
                } else {
                    current.append(c);
                }
            } else if (inDoubleQuote) {
                if (c == '"') {
                    inDoubleQuote = false;
                } else if (c == '\\' && i + 1 < args.length()
                        && (args.charAt(i + 1) == '"' || args.charAt(i + 1) == '\\')) {
                    current.append(args.charAt(++i));
                } else {
                    current.append(c);
                }
            } else if (Character.isWhitespace(c)) {
                if (hasToken) {
                    tokens.add(current.toString());
                    current.setLength(0);
                    hasToken = false;
                }
            } else if (c == '\'') {
                inSingleQuote = true;
                hasToken = true;
            } else if (c == '"') {
                inDoubleQuote = true;
                hasToken = true;
            } else {
                current.append(c);
                hasToken = true;
            }
        }
        if (hasToken) tokens.add(current.toString());
        return tokens;
    }

    private static String run(List<String> cmd, Path cwd) {
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd).redirectErrorStream(true);
            if (cwd != null) {
                pb.directory(cwd.toFile());
            }
            Process process = pb.start();
            String output = new String(process.getInputStream().readAllBytes());
            int exitCode = process.waitFor();
            if (exitCode != 0) {
                log.warn("Script '{}' exited with code {}: {}", cmd, exitCode, output.trim());
            }
            return output.trim();
        } catch (Exception e) {
            log.error("Script execution failed: {}", cmd, e);
            return "Script error: " + e.getMessage();
        }
    }
}
