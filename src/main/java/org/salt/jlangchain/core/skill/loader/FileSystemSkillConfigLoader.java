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

import lombok.extern.slf4j.Slf4j;
import org.salt.jlangchain.core.skill.ScriptDef;
import org.salt.jlangchain.core.skill.ScriptTool;
import org.salt.jlangchain.core.skill.SkillConfig;
import org.salt.jlangchain.core.subagent.SubAgentConfig;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Loads a {@link SkillConfig} from a filesystem directory following the same
 * SKILL.md layout as {@link ClasspathSkillConfigLoader}, but reading from
 * {@link java.nio.file.Path} instead of classpath resources.
 *
 * <pre>
 * {skillDir}/
 *   SKILL.md          → frontmatter (name, description, allowed-tools) + body (systemPrompt)
 *   references/*.md   → domain knowledge injected into systemPrompt
 *   scripts/*         → executable scripts converted to ScriptDef
 *   agents/*.md       → embedded sub-agents
 * </pre>
 */
@Slf4j
public class FileSystemSkillConfigLoader implements SkillConfigLoader {

    @Override
    public SkillConfig load(String skillDir) {
        return fromPath(Path.of(skillDir));
    }

    public static SkillConfig fromPath(Path skillDir) {
        return new FileSystemSkillConfigLoader().loadFromPath(skillDir);
    }

    // ── core ─────────────────────────────────────────────────────────────────

    private SkillConfig loadFromPath(Path dir) {
        Path skillMd = dir.resolve("SKILL.md");
        if (!Files.exists(skillMd)) {
            throw new IllegalArgumentException("SKILL.md not found in: " + dir);
        }

        String content = readFile(skillMd);
        SkillMdParsed parsed = parseSkillMd(content);
        List<String> references = loadReferences(dir);
        List<ScriptDef> scripts = loadScripts(dir);
        List<SubAgentConfig> agents = loadAgents(dir);

        return SkillConfig.builder()
                .name(parsed.name())
                .description(parsed.description())
                .allowedTools(parsed.allowedTools())
                .systemPrompt(parsed.body())
                .references(references)
                .scripts(scripts)
                .agents(agents)
                .maxIterations(parsed.maxIterations())
                .build();
    }

    // ── SKILL.md parsing ─────────────────────────────────────────────────────

    private SkillMdParsed parseSkillMd(String content) {
        String[] parts = content.split("(?m)^---\\s*$", 3);
        if (parts.length < 3) {
            return new SkillMdParsed("", "", List.of(), null, content.trim());
        }
        Map<String, Object> fm = parseFrontmatter(parts[1].trim());
        String body = parts[2].trim();
        return new SkillMdParsed(
                getString(fm, "name", ""),
                getString(fm, "description", ""),
                getStringList(fm, "allowed-tools"),
                getInteger(fm, "max-iterations"),
                body
        );
    }

    // ── references loading ───────────────────────────────────────────────────

    private List<String> loadReferences(Path dir) {
        Path refDir = dir.resolve("references");
        if (!Files.isDirectory(refDir)) return List.of();
        try (Stream<Path> files = Files.list(refDir)) {
            return files.filter(p -> p.getFileName().toString().endsWith(".md"))
                    .sorted()
                    .map(this::readFile)
                    .filter(c -> c != null && !c.isBlank())
                    .toList();
        } catch (IOException e) {
            log.debug("No references in {}/references/", dir);
            return List.of();
        }
    }

    // ── scripts loading ──────────────────────────────────────────────────────

    private List<ScriptDef> loadScripts(Path dir) {
        Path scriptsDir = dir.resolve("scripts");
        if (!Files.isDirectory(scriptsDir)) return List.of();
        List<ScriptDef> scripts = new ArrayList<>();
        try (Stream<Path> files = Files.list(scriptsDir)) {
            files.filter(Files::isRegularFile).sorted().forEach(p -> {
                String filename = p.getFileName().toString();
                if (!filename.contains(".")) return;
                String ext = filename.substring(filename.lastIndexOf('.') + 1).toLowerCase();
                if (!ScriptTool.supports(ext)) return;
                String name = filename.substring(0, filename.lastIndexOf('.'));
                String content = readFile(p);
                if (content != null) {
                    scripts.add(ScriptDef.builder().name(name).type(ext).content(content).build());
                }
            });
        } catch (IOException e) {
            log.debug("No scripts in {}/scripts/", dir);
        }
        return scripts;
    }

    // ── agents loading ───────────────────────────────────────────────────────

    private List<SubAgentConfig> loadAgents(Path dir) {
        Path agentsDir = dir.resolve("agents");
        if (!Files.isDirectory(agentsDir)) return List.of();
        List<SubAgentConfig> agents = new ArrayList<>();
        try (Stream<Path> files = Files.list(agentsDir)) {
            files.filter(p -> p.getFileName().toString().endsWith(".md")).sorted().forEach(p -> {
                String filename = p.getFileName().toString();
                String name = filename.substring(0, filename.length() - 3);
                String content = readFile(p);
                if (content == null || content.isBlank()) return;

                String[] parts = content.split("(?m)^---\\s*$", 3);
                String body;
                String model = null;
                String description = extractFirstHeading(content);
                List<String> allowedTools = List.of();
                if (parts.length >= 3) {
                    Map<String, Object> fm = parseFrontmatter(parts[1].trim());
                    body = parts[2].trim();
                    model = getString(fm, "model", null);
                    allowedTools = getStringList(fm, "allowed-tools");
                    String fmDesc = getString(fm, "description", null);
                    if (fmDesc != null && !fmDesc.isBlank()) description = fmDesc;
                    String fmName = getString(fm, "name", null);
                    if (fmName != null && !fmName.isBlank()) name = fmName;
                } else {
                    body = content.trim();
                }
                agents.add(SubAgentConfig.builder()
                        .name(name).description(description)
                        .model(model).allowedTools(allowedTools)
                        .systemPrompt(body).build());
            });
        } catch (IOException e) {
            log.debug("No agents in {}/agents/", dir);
        }
        return agents;
    }

    private String extractFirstHeading(String content) {
        for (String line : content.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.startsWith("# "))  return trimmed.substring(2).trim();
            if (trimmed.startsWith("## ")) return trimmed.substring(3).trim();
        }
        return "";
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private String readFile(Path path) {
        try {
            return Files.readString(path);
        } catch (IOException e) {
            log.warn("Failed to read: {}", path, e);
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseFrontmatter(String yaml) {
        try {
            Map<String, Object> result = new Yaml().load(yaml);
            return result != null ? result : Map.of();
        } catch (Exception e) {
            log.warn("Failed to parse frontmatter: {}", e.getMessage());
            return Map.of();
        }
    }

    private String getString(Map<String, Object> map, String key, String defaultVal) {
        Object val = map.get(key);
        return val != null ? val.toString() : defaultVal;
    }

    private Integer getInteger(Map<String, Object> map, String key) {
        Object val = map.get(key);
        if (val == null) return null;
        try { return Integer.parseInt(val.toString()); }
        catch (NumberFormatException e) { return null; }
    }

    private List<String> getStringList(Map<String, Object> map, String key) {
        Object val = map.get(key);
        if (val == null) return List.of();
        if (val instanceof List<?> list) return list.stream().map(Object::toString).toList();
        if (val instanceof String str) {
            return Arrays.stream(str.split(",")).map(String::trim).filter(s -> !s.isBlank()).toList();
        }
        return List.of();
    }

    private record SkillMdParsed(String name, String description, List<String> allowedTools,
                                  Integer maxIterations, String body) {}
}
