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
import org.salt.jlangchain.core.skill.loader.ClasspathSkillConfigLoader;
import org.salt.jlangchain.core.subagent.SubAgentConfig;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Loads a {@link SubAgentConfig} from classpath resources following the AGENT.md
 * directory layout:
 *
 * <pre>
 * {agentDir}/
 *   AGENT.md          → frontmatter (name, description, skills, max-iterations) + body (systemPrompt)
 * </pre>
 *
 * The {@code skills} frontmatter field is a list of classpath directories; each is loaded
 * via {@link ClasspathSkillConfigLoader} and stored inline in {@link SubAgentConfig#getSkills()}.
 *
 * <p>Works inside JAR files via Spring's {@link PathMatchingResourcePatternResolver}.
 */
@Slf4j
public class ClasspathSubAgentConfigLoader implements SubAgentConfigLoader {

    private static final PathMatchingResourcePatternResolver RESOLVER =
            new PathMatchingResourcePatternResolver();

    @Override
    public SubAgentConfig load(String agentDir) {
        return loadFromClasspath(agentDir);
    }

    /** Static convenience entry point. */
    public static SubAgentConfig fromClasspath(String agentDir) {
        return new ClasspathSubAgentConfigLoader().loadFromClasspath(agentDir);
    }

    // ── core ─────────────────────────────────────────────────────────────────

    private SubAgentConfig loadFromClasspath(String rawDir) {
        String dir = normalize(rawDir);

        String agentMdContent = readResource("classpath:" + dir + "/AGENT.md");
        if (agentMdContent == null) {
            throw new IllegalArgumentException("AGENT.md not found in classpath: " + dir);
        }

        AgentMdParsed parsed = parseAgentMd(agentMdContent);
        List<SkillConfig> skills = loadSkills(parsed.skillDirs());

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

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseFrontmatter(String yaml) {
        try {
            Map<String, Object> result = new Yaml().load(yaml);
            return result != null ? result : Map.of();
        } catch (Exception e) {
            log.warn("Failed to parse AGENT.md frontmatter: {}", e.getMessage());
            return Map.of();
        }
    }

    // ── skill loading ─────────────────────────────────────────────────────────

    private List<SkillConfig> loadSkills(List<String> skillDirs) {
        if (skillDirs == null || skillDirs.isEmpty()) return List.of();
        List<SkillConfig> skills = new ArrayList<>();
        for (String dir : skillDirs) {
            try {
                skills.add(ClasspathSkillConfigLoader.fromClasspath(dir));
            } catch (Exception e) {
                log.warn("Failed to load skill '{}' for sub-agent: {}", dir, e.getMessage());
            }
        }
        return skills;
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
        return (val != null && !val.toString().isBlank()) ? val.toString() : defaultVal;
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

    private record AgentMdParsed(String name, String description, String model,
                                  List<String> allowedTools, List<String> skillDirs,
                                  Integer maxIterations, String body) {}
}
