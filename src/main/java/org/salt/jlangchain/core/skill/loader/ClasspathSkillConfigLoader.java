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
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Loads a {@link SkillConfig} from classpath resources following the
 * Claude Code SKILL.md directory layout:
 *
 * <pre>
 * {skillDir}/
 *   SKILL.md          → frontmatter (name, description, allowed-tools) + body (systemPrompt)
 *   references/*.md   → domain knowledge injected into systemPrompt
 *   scripts/*         → executable scripts converted to ScriptDef
 *   agents/*.md       → embedded sub-agents (no frontmatter; name from filename, body as systemPrompt)
 * </pre>
 *
 * Works inside JAR files via Spring's {@link PathMatchingResourcePatternResolver}.
 */
@Slf4j
public class ClasspathSkillConfigLoader implements SkillConfigLoader {

    private static final PathMatchingResourcePatternResolver RESOLVER =
            new PathMatchingResourcePatternResolver();

    /** Instance-based entry point (satisfies {@link SkillConfigLoader} interface). */
    @Override
    public SkillConfig load(String skillDir) {
        return loadFromClasspath(skillDir);
    }

    /** Static convenience entry point. */
    public static SkillConfig fromClasspath(String skillDir) {
        return new ClasspathSkillConfigLoader().loadFromClasspath(skillDir);
    }

    // ── core ─────────────────────────────────────────────────────────────────

    private SkillConfig loadFromClasspath(String rawDir) {
        String dir = normalize(rawDir);

        String skillMdContent = readResource("classpath:" + dir + "/SKILL.md");
        if (skillMdContent == null) {
            throw new IllegalArgumentException("SKILL.md not found in classpath: " + dir);
        }

        SkillMdParsed parsed = parseSkillMd(skillMdContent);
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
        // Split on "---" frontmatter delimiters (max 3 parts)
        String[] parts = content.split("(?m)^---\\s*$", 3);
        if (parts.length < 3) {
            // No frontmatter — treat the whole file as systemPrompt
            return new SkillMdParsed("", "", List.of(), null, content.trim());
        }

        // parts[0] = "" (before first ---), parts[1] = YAML, parts[2] = body
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

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseFrontmatter(String yaml) {
        try {
            Map<String, Object> result = new Yaml().load(yaml);
            return result != null ? result : Map.of();
        } catch (Exception e) {
            log.warn("Failed to parse SKILL.md frontmatter: {}", e.getMessage());
            return Map.of();
        }
    }

    // ── references loading ───────────────────────────────────────────────────

    private List<String> loadReferences(String dir) {
        try {
            Resource[] resources = RESOLVER.getResources("classpath:" + dir + "/references/*.md");
            List<String> refs = new ArrayList<>();
            for (Resource r : resources) {
                String content = readInputStream(r.getInputStream());
                if (content != null && !content.isBlank()) {
                    refs.add(content);
                }
            }
            return refs;
        } catch (IOException e) {
            log.debug("No references in {}/references/", dir);
            return List.of();
        }
    }

    // ── scripts loading ──────────────────────────────────────────────────────

    private List<ScriptDef> loadScripts(String dir) {
        try {
            Resource[] resources = RESOLVER.getResources("classpath:" + dir + "/scripts/*");
            List<ScriptDef> scripts = new ArrayList<>();
            for (Resource r : resources) {
                String filename = r.getFilename();
                if (filename == null || !filename.contains(".")) continue;
                String ext = filename.substring(filename.lastIndexOf('.') + 1).toLowerCase();
                if (!ScriptTool.supports(ext)) continue;
                String name = filename.substring(0, filename.lastIndexOf('.'));
                String content = readInputStream(r.getInputStream());
                if (content != null) {
                    scripts.add(ScriptDef.builder().name(name).type(ext).content(content).build());
                }
            }
            return scripts;
        } catch (IOException e) {
            log.debug("No scripts in {}/scripts/", dir);
            return List.of();
        }
    }

    // ── agents loading ───────────────────────────────────────────────────────

    private List<SubAgentConfig> loadAgents(String dir) {
        try {
            Resource[] resources = RESOLVER.getResources("classpath:" + dir + "/agents/*.md");
            List<SubAgentConfig> agents = new ArrayList<>();
            for (Resource r : resources) {
                String filename = r.getFilename();
                if (filename == null) continue;
                String name = filename.endsWith(".md")
                        ? filename.substring(0, filename.length() - 3)
                        : filename;
                String content = readInputStream(r.getInputStream());
                if (content == null || content.isBlank()) continue;

                // Agent files may or may not have frontmatter
                String[] parts = content.split("(?m)^---\\s*$", 3);
                String body;
                String model = null;
                String description = extractFirstHeading(content);
                if (parts.length >= 3) {
                    Map<String, Object> fm = parseFrontmatter(parts[1].trim());
                    body = parts[2].trim();
                    model = getString(fm, "model", null);
                    String fmDesc = getString(fm, "description", null);
                    if (fmDesc != null && !fmDesc.isBlank()) description = fmDesc;
                    String fmName = getString(fm, "name", null);
                    if (fmName != null && !fmName.isBlank()) name = fmName;
                } else {
                    body = content.trim();
                }

                agents.add(SubAgentConfig.builder()
                        .name(name)
                        .description(description)
                        .model(model)
                        .systemPrompt(body)
                        .build());
            }
            return agents;
        } catch (IOException e) {
            log.debug("No agents in {}/agents/", dir);
            return List.of();
        }
    }

    /** Extract first H1/H2 heading as a short description fallback. */
    private String extractFirstHeading(String content) {
        for (String line : content.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.startsWith("# "))  return trimmed.substring(2).trim();
            if (trimmed.startsWith("## ")) return trimmed.substring(3).trim();
        }
        return "";
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private String readResource(String location) {
        try {
            Resource resource = RESOLVER.getResource(location);
            if (!resource.exists()) return null;
            return readInputStream(resource.getInputStream());
        } catch (IOException e) {
            return null;
        }
    }

    private String readInputStream(InputStream is) {
        try (is) {
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.warn("Failed to read resource stream", e);
            return null;
        }
    }

    private String normalize(String dir) {
        String d = dir.startsWith("/") ? dir.substring(1) : dir;
        return d.endsWith("/") ? d.substring(0, d.length() - 1) : d;
    }

    private String getString(Map<String, Object> map, String key, String defaultVal) {
        Object val = map.get(key);
        return val != null ? val.toString() : defaultVal;
    }

    private Integer getInteger(Map<String, Object> map, String key) {
        Object val = map.get(key);
        if (val == null) return null;
        try {
            return Integer.parseInt(val.toString());
        } catch (NumberFormatException e) {
            log.warn("Invalid integer value for '{}': {}", key, val);
            return null;
        }
    }

    private List<String> getStringList(Map<String, Object> map, String key) {
        Object val = map.get(key);
        if (val == null) return List.of();
        if (val instanceof List<?> list) {
            return list.stream().map(Object::toString).toList();
        }
        if (val instanceof String str) {
            return Arrays.stream(str.split(","))
                    .map(String::trim)
                    .filter(s -> !s.isBlank())
                    .toList();
        }
        return List.of();
    }

    private record SkillMdParsed(String name, String description, List<String> allowedTools, Integer maxIterations, String body) {}
}
