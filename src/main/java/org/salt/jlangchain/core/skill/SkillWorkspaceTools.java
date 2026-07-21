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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Scoped filesystem tools for {@link Skill}'s "Claude-compatible" execution mode (see
 * {@link SkillConfig#setClaudeCompatMode(boolean)} laudeCompatMode}) — a minimal stand-in for the generic filesystem
 * access Claude Code always gives its skills. Without this, a skill with no {@code
 * allowed-tools} (true of virtually every real Claude Code skill — that frontmatter field is a
 * j-langchain extension) has zero tools to create, inspect, or write the files it needs.
 *
 * <p>Every tool here resolves its {@code path} argument against a fixed {@code root} directory
 * and rejects anything that would escape it — an absolute path outside root, {@code ..}
 * traversal, or a symlink that resolves outside root — so a skill running in this mode can only
 * ever touch files inside its own workspace. This is intentionally narrower than a generic Bash
 * tool: no arbitrary command execution, only the five filesystem operations a skill genuinely
 * needs to build up its own working directory.
 */
@Slf4j
public final class SkillWorkspaceTools {

    private SkillWorkspaceTools() {
    }

    public static List<Tool> forRoot(Path root) {
        try {
            Files.createDirectories(root);
        } catch (IOException e) {
            throw new RuntimeException("Failed to create skill workspace: " + root, e);
        }
        Path canonicalRoot = canonicalize(root);
        return List.of(
                createDirectoryTool(canonicalRoot),
                writeFileTool(canonicalRoot),
                readFileTool(canonicalRoot),
                listDirectoryTool(canonicalRoot),
                fileExistsTool(canonicalRoot)
        );
    }

    // ── tools ────────────────────────────────────────────────────────────────

    private static Tool createDirectoryTool(Path root) {
        return Tool.builder()
                .name("create_directory")
                .description("[workspace] Create a directory (and any missing parents) inside the skill's "
                        + "sandboxed workspace. path is relative to the workspace root. Returns the absolute "
                        + "path — pass that (not the relative path) when invoking bundled scripts that expect "
                        + "a real filesystem path.")
                .params("path: String")
                .func(args -> {
                    Path target = resolve(root, argString(args, "path"));
                    try {
                        Files.createDirectories(target);
                        return "Created: " + target;
                    } catch (IOException e) {
                        return "Error: " + e.getMessage();
                    }
                })
                .build();
    }

    private static Tool writeFileTool(Path root) {
        return Tool.builder()
                .name("write_file")
                .description("[workspace] Write (create or overwrite) a text file inside the skill's sandboxed "
                        + "workspace. path is relative to the workspace root; missing parent directories are "
                        + "created automatically. Returns the absolute path — pass that (not the relative path) "
                        + "when invoking bundled scripts that expect a real filesystem path.")
                .params("path: String, content: String")
                .func(args -> {
                    Path target = resolve(root, argString(args, "path"));
                    String content = argString(args, "content");
                    try {
                        if (target.getParent() != null) {
                            Files.createDirectories(target.getParent());
                        }
                        Files.writeString(target, content);
                        return "Wrote " + content.length() + " chars to " + target;
                    } catch (IOException e) {
                        return "Error: " + e.getMessage();
                    }
                })
                .build();
    }

    private static Tool readFileTool(Path root) {
        return Tool.builder()
                .name("read_file")
                .description("[workspace] Read a text file inside the skill's sandboxed workspace. "
                        + "path is relative to the workspace root.")
                .params("path: String")
                .func(args -> {
                    Path target = resolve(root, argString(args, "path"));
                    if (!Files.exists(target)) {
                        return "Error: file not found: " + relative(root, target);
                    }
                    try {
                        return Files.readString(target);
                    } catch (IOException e) {
                        return "Error: " + e.getMessage();
                    }
                })
                .build();
    }

    private static Tool listDirectoryTool(Path root) {
        return Tool.builder()
                .name("list_directory")
                .description("[workspace] List the entries of a directory inside the skill's sandboxed workspace. "
                        + "path is relative to the workspace root; use \".\" for the workspace root itself.")
                .params("path: String")
                .func(args -> {
                    String raw = argString(args, "path");
                    Path target = resolve(root, raw.isBlank() ? "." : raw);
                    if (!Files.isDirectory(target)) {
                        return "Error: not a directory: " + relative(root, target);
                    }
                    try (Stream<Path> entries = Files.list(target)) {
                        List<String> names = entries.sorted()
                                .map(p -> Files.isDirectory(p) ? p.getFileName() + "/" : p.getFileName().toString())
                                .collect(Collectors.toList());
                        return names.isEmpty() ? "(empty)" : String.join("\n", names);
                    } catch (IOException e) {
                        return "Error: " + e.getMessage();
                    }
                })
                .build();
    }

    private static Tool fileExistsTool(Path root) {
        return Tool.builder()
                .name("file_exists")
                .description("[workspace] Check whether a file or directory exists inside the skill's sandboxed "
                        + "workspace. path is relative to the workspace root.")
                .params("path: String")
                .func(args -> Boolean.toString(Files.exists(resolve(root, argString(args, "path")))))
                .build();
    }

    /**
     * Optional scoped Bash tool — NOT a real security sandbox (a command can still {@code cd}
     * elsewhere or reference absolute paths); it only fixes the process's working directory to
     * {@code root}. Never granted automatically, even in Claude-compatible mode — only offered
     * when a caller explicitly opts in via {@code Skill.Builder#claudeCompatBash(boolean)}.
     */
    public static Tool scopedBashTool(Path root) {
        return Tool.builder()
                .name("bash")
                .description("[workspace] Run a shell command with cwd fixed to the skill's sandboxed workspace "
                        + "root. Not a hard sandbox — avoid absolute paths or commands that leave the workspace.")
                .params("command: String")
                .func(args -> {
                    String command = argString(args, "command");
                    if (command.isBlank()) return "Error: empty command";
                    try {
                        Process process = new ProcessBuilder("bash", "-c", command)
                                .directory(root.toFile())
                                .redirectErrorStream(true)
                                .start();
                        String output = new String(process.getInputStream().readAllBytes());
                        int exitCode = process.waitFor();
                        return exitCode == 0 ? output.trim() : "Exit " + exitCode + ": " + output.trim();
                    } catch (Exception e) {
                        return "Error: " + e.getMessage();
                    }
                })
                .build();
    }

    // ── path safety ──────────────────────────────────────────────────────────

    /**
     * Resolves {@code rawPath} against {@code root}, rejecting anything that would escape it:
     * an absolute path outside root, {@code ..} traversal past root, or a symlink whose real
     * target resolves outside root. The target itself need not exist yet (e.g. a file about to
     * be written) — in that case the nearest existing ancestor is what gets canonicalized and
     * checked, since {@link Path#toRealPath} requires the path to exist.
     */
    static Path resolve(Path root, String rawPath) {
        if (rawPath == null || rawPath.isBlank() || rawPath.equals(".")) {
            return root;
        }
        Path candidate = root.resolve(rawPath).normalize();
        if (!candidate.equals(root) && !candidate.startsWith(root)) {
            throw new SecurityException("Path escapes workspace: " + rawPath);
        }
        Path existingAncestor = candidate;
        while (existingAncestor != null && !Files.exists(existingAncestor)) {
            existingAncestor = existingAncestor.getParent();
        }
        if (existingAncestor != null) {
            Path canonicalAncestor = canonicalize(existingAncestor);
            if (!canonicalAncestor.equals(root) && !canonicalAncestor.startsWith(root)) {
                throw new SecurityException("Path escapes workspace (symlink): " + rawPath);
            }
        }
        return candidate;
    }

    private static Path canonicalize(Path p) {
        try {
            return p.toRealPath();
        } catch (IOException e) {
            return p.toAbsolutePath().normalize();
        }
    }

    private static String relative(Path root, Path target) {
        try {
            return root.relativize(target).toString();
        } catch (IllegalArgumentException e) {
            return target.toString();
        }
    }

    @SuppressWarnings("unchecked")
    private static String argString(Object raw, String key) {
        if (!(raw instanceof Map<?, ?> map)) return "";
        Object v = map.get(key);
        return v != null ? v.toString() : "";
    }
}
