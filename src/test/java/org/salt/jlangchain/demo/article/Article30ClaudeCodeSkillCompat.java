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

package org.salt.jlangchain.demo.article;

import org.junit.Assume;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.salt.jlangchain.TestApplication;
import org.salt.jlangchain.core.ChainActor;
import org.salt.jlangchain.core.llm.aliyun.ChatAliyun;
import org.salt.jlangchain.core.skill.ReferenceDoc;
import org.salt.jlangchain.core.skill.ReferencesMode;
import org.salt.jlangchain.core.skill.Skill;
import org.salt.jlangchain.core.skill.SkillConfig;
import org.salt.jlangchain.core.skill.loader.FileSystemSkillConfigLoader;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.junit4.SpringRunner;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.*;

/**
 * 文章 30：加载一个真实的 Claude Code Skill 目录，验证 skill-claude-code-compat.md 的兼容性设计。
 *
 * <p>技能来源：本机已安装的 claude-plugins-official 插件 {@code claude-md-management}，
 * 目录里只有 {@code SKILL.md} + {@code references/}，没有 {@code scripts/}/{@code agents/}
 * 这两个 j-langchain 扩展目录，适合验证"纯规范目录"能否被原样加载。
 *
 * <p>该技能不是本仓库资源，依赖本机 {@code ~/.claude} 插件安装状态；目录不存在时用
 * {@link Assume} 跳过，不影响没有装这个插件的机器/CI。
 */
@RunWith(SpringRunner.class)
@SpringBootTest(classes = TestApplication.class)
@SpringBootConfiguration
public class Article30ClaudeCodeSkillCompat {

    private static final Path SKILL_DIR = Path.of(System.getProperty("user.home"),
            ".claude", "plugins", "marketplaces", "claude-plugins-official",
            "plugins", "claude-md-management", "skills", "claude-md-improver");

    @Autowired
    private ChainActor chainActor;

    // ── 测试 1：加载真实 Skill 目录，校验前言/references 解析 ─────────────────

    @Test
    public void testLoadRealClaudeCodeSkill_parsesFrontmatterAndReferences() {
        Assume.assumeTrue("跳过：本机未安装 claude-md-management 插件 (" + SKILL_DIR + ")",
                Files.isDirectory(SKILL_DIR));

        SkillConfig config = FileSystemSkillConfigLoader.fromPath(SKILL_DIR);

        assertEquals("claude-md-improver", config.getName());
        assertTrue(config.getDescription().toLowerCase().contains("claude.md"));

        // SKILL.md 用的是 "tools:" 字段（不是我们认识的 "allowed-tools"），
        // 应该被静默忽略，不报错、不影响其余字段解析。
        assertTrue(config.getAllowedTools() == null || config.getAllowedTools().isEmpty());

        // 没有声明 references-mode，应落到默认值 INLINE（向下兼容）。
        assertEquals(ReferencesMode.INLINE, config.getReferencesMode());

        assertNotNull(config.getReferences());
        assertEquals(3, config.getReferences().size());
        for (ReferenceDoc ref : config.getReferences()) {
            assertFalse(ref.content().isBlank());
            assertFalse(ref.summary().isBlank());
            System.out.printf("  reference: %-24s %s%n", ref.filename(), ref.summary());
        }

        // scripts/ 和 agents/ 目录在这个技能包里不存在，应安全返回空列表，不报错。
        assertTrue(config.getScripts() == null || config.getScripts().isEmpty());
        assertTrue(config.getAgents() == null || config.getAgents().isEmpty());
    }

    // ── 测试 2：以 lazy 模式独立运行该技能，验证 read_reference 工具被实际使用 ──

    @Test
    public void testInvokeRealSkillStandalone_withLazyReferences() throws IOException {
        Assume.assumeTrue("跳过：本机未安装 claude-md-management 插件 (" + SKILL_DIR + ")",
                Files.isDirectory(SKILL_DIR));

        Path agentsMd = Path.of("AGENTS.md");
        Assume.assumeTrue("跳过：找不到本仓库 AGENTS.md", Files.exists(agentsMd));
        String repoGuideline = Files.readString(agentsMd);

        SkillConfig baseConfig = FileSystemSkillConfigLoader.fromPath(SKILL_DIR);
        // 覆盖为 lazy 模式：references 不再整份塞进 systemPrompt，
        // 而是变成一份文件清单 + read_reference(file) 工具，由模型按需读取。
        SkillConfig lazyConfig = baseConfig.toBuilder()
                .referencesMode(ReferencesMode.LAZY)
                .build();

        Skill skill = Skill.from(lazyConfig, chainActor)
                .llm(ChatAliyun.builder().model("qwen-plus").temperature(0f).build())
                .onToolCall(tc -> System.out.println("[ToolCall]    " + tc))
                .onObservation(obs -> {
                    String preview = obs.length() > 200 ? obs.substring(0, 200) + "..." : obs;
                    System.out.println("[Observation] " + preview);
                })
                .build();

        String result = skill.invoke(
                "下面是本仓库 j-langchain 的 AGENTS.md 内容。在打分前，请先调用 read_reference 工具读取 "
                        + "quality-criteria.md 的完整评分细则（不要凭记忆猜测评分项和权重），再据此给出质量评估和改进建议。"
                        + "不需要用 Bash/Find 去扫描仓库，直接分析我给你的内容即可：\n\n" + repoGuideline);

        System.out.println("\n========== claude-md-improver 评估结果 ==========");
        System.out.println(result);
        assertFalse(result.isBlank());
    }
}
