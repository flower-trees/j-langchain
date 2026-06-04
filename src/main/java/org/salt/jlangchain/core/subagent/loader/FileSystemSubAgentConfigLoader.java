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

package org.salt.jlangchain.core.subagent.loader;

import lombok.extern.slf4j.Slf4j;
import org.salt.jlangchain.core.skill.SkillConfig;
import org.salt.jlangchain.core.skill.loader.FileSystemSkillConfigLoader;
import org.salt.jlangchain.core.subagent.SubAgentConfig;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * Loads a {@link SubAgentConfig} from a filesystem directory following the same
 * AGENT.md layout as {@link ClasspathSubAgentConfigLoader}, but reading from
 * {@link java.nio.file.Path} instead of classpath resources.
 *
 * <pre>
 * {agentDir}/
 *   AGENT.md          → frontmatter (name, description, model, skills, max-iterations) + body
 *   skills/           → skill subdirs resolved via FileSystemSkillConfigLoader
 * </pre>
 *
 * The {@code skills} frontmatter field lists subdirectory names relative to the
 * agent directory (e.g. {@code skills: [search_skill, summarize_skill]}).
 */
@Slf4j
public class FileSystemSubAgentConfigLoader implements SubAgentConfigLoader {

    @Override
    public SubAgentConfig load(String agentDir) {
        return fromPath(Path.of(agentDir));
    }

    public static SubAgentConfig fromPath(Path agentDir) {
        return new FileSystemSubAgentConfigLoader().loadFromPath(agentDir);
    }

    // ── core ─────────────────────────────────────────────────────────────────

    private SubAgentConfig loadFromPath(Path dir) {
        Path agentMd = dir.resolve("AGENT.md");
        if (!Files.exists(agentMd)) {
            throw new IllegalArgumentException("AGENT.md not found in: " + dir);
        }

        String content = readFile(agentMd);
        AgentMdParsed parsed = parseAgentMd(content);
        List<SkillConfig> skills = loadSkills(dir, parsed.skillDirs());

        return SubAgentConfig.builder()
                .name(parsed.name())
                .description(parsed.description())
                .model(parsed.model())
                .allowedTools(parsed.allowedTools())
                .systemPrompt(parsed.body())
                .skills(skills)
                .maxIterations(parsed.maxIterations())
                .build();
    }

    // ── AGENT.md parsing ─────────────────────────────────────────────────────

    private AgentMdParsed parseAgentMd(String content) {
        String[] parts = content.split("(?m)^---\\s*$", 3);
        if (parts.length < 3) {
            return new AgentMdParsed("", "", null, List.of(), List.of(), null, content.trim());
        }
        Map<String, Object> fm = parseFrontmatter(parts[1].trim());
        String body = parts[2].trim();
        return new AgentMdParsed(
                getString(fm, "name", ""),
                getString(fm, "description", ""),
                getString(fm, "model", null),
                getStringList(fm, "tools"),
                getStringList(fm, "skills"),
                getInteger(fm, "max-iterations"),
                body
        );
    }

    // ── skill loading ─────────────────────────────────────────────────────────

    private List<SkillConfig> loadSkills(Path baseDir, List<String> skillDirs) {
        if (skillDirs == null || skillDirs.isEmpty()) return List.of();
        List<SkillConfig> skills = new ArrayList<>();
        for (String skillDir : skillDirs) {
            // Resolve relative to the agent directory (or absolute if given)
            Path skillPath = Path.of(skillDir).isAbsolute() ? Path.of(skillDir) : baseDir.resolve(skillDir);
            try {
                skills.add(FileSystemSkillConfigLoader.fromPath(skillPath));
            } catch (Exception e) {
                log.warn("Failed to load skill '{}': {}", skillPath, e.getMessage());
            }
        }
        return skills;
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
        return (val != null && !val.toString().isBlank()) ? val.toString() : defaultVal;
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

    private record AgentMdParsed(String name, String description, String model,
                                  List<String> allowedTools, List<String> skillDirs,
                                  Integer maxIterations, String body) {}
}
