# Skill 兼容 Claude Code Skill 规范设计

> 目标：让 `org.salt.jlangchain.core.skill` 现有实现能够**合理兼容**一个按 Claude Code Skill 规范编写的 `SKILL.md` 技能目录——含义正确、能跑通即可，不要求跟 Claude Code 内部实现逐字节一致。本设计基于对现有 [skill.md](./skill.md) 架构与代码（`Skill.java`/`SkillConfig.java`/`ClasspathSkillConfigLoader.java`/`FileSystemSkillConfigLoader.java`）的走查。

---

## 1. 背景

现有 Skill 实现在 `skill.md` 里明确写了"Maps to the Claude Code SKILL.md format"，前言字段（`name`/`description`/`allowed-tools`）、目录约定（`references/`/`scripts/`/`agents/`）都是照着 Claude Code 的样子设计的。走查代码后，找出几处需要澄清或补齐的地方，逐一给出方案。

技能的统一调度/多目录发现（即"很多个 Skill 怎么被主 Agent 统一管理和路由"）不在本文档范围内——那属于更上层的架构设计，会在别处单独规划，这里只关注单个 `SkillConfig`/`Skill` 的加载与运行是否兼容。

---

## 2. 差距分析

### 2.1 `allowed-tools` 语义 —— 结论：现状已经是对的，不需要改代码

最初怀疑这里有语义冲突（"权限收紧" vs "权限授予"），重新审视后发现是虚惊一场：

- Claude Code 里，`allowed-tools` 限制的是"技能运行期间能用主 Agent 的哪些工具"——因为 Claude Code 的技能就运行在主 Agent 会话里，Bash/Read/Edit 这些"内置工具"本质上就是**主 Agent 自己的工具**，没有第二个工具来源。
- j-langchain 的 Skill 架构里，工具来源分三层（脚本/自有/借用父级），但其中"脚本工具"和"自有工具"是 Skill 作者在构建时**显式挑选**、已经是个封闭小集合，天然不需要再收紧；唯一一个"开放的、可能很大的外部工具面"就是**主 Agent 的工具**——也就是 `parentTools`。
- 所以现状 `allowedTools`（只过滤 `parentTools` 这一层，决定借用主 Agent 哪些工具）和 Claude Code 的"限制技能可用主 Agent 工具范围"其实是同一件事，只是 j-langchain 用"白名单借用"的方式实现了同样的收紧效果。

**结论**：不拆字段、不新增 `borrow-tools`（这个字段是我之前的臆造，Claude Code 规范里并没有这个字段，作废）。唯一要做的是把这段"为什么现状就是对的"的设计意图写进 `skill.md`，避免以后有人看代码又觉得这是个 bug。

### 2.2 references 渐进式加载未落实（保持现状默认值，新增可选项）

Claude Code Skill 的运行时行为是三级渐进加载：

```
Level 1  name + description         永远在主模型上下文里（"技能目录"）
Level 2  SKILL.md 正文               仅当该 Skill 被调用时才加载
Level 3  references/scripts 等文件    仅当 Skill 自己判断需要时才按需读取，不预加载
```

- **Level 2 已经符合**：`Skill.buildExecutor()` 是懒初始化，`systemPrompt` 只在首次 `invoke()` 时才真正拼装，没问题。
- **Level 3 不符合**：`loadReferences()` 在 loader 阶段就把 `references/*.md` 全量读入内存，`buildSystemPrompt()` 无条件把全文拼进 systemPrompt，不管这次调用用不用得上。

**方案**：`SkillConfig.references` 从 `List<String>` 改为 `List<ReferenceDoc>`（文件名 + 全文 + 可选摘要），新增开关，**默认值保持现状行为**，不做 breaking change：

```yaml
references-mode: inline   # inline(默认，保留现状) | lazy(新增，可选)
```

- `inline`（默认）：行为不变，全文拼进 systemPrompt。
- `lazy`（可选）：systemPrompt 只追加一份文件清单（文件名 + 摘要），并自动挂一个 `read_reference(file: String)` 工具，Skill 内部执行器需要时自己去读全文——贴近 Claude Code 的 Level 3 行为，供体量较大的 reference 文档使用。

### 2.3 `scripts/` 与 `agents/` 目录：现状是 j-langchain 的增值扩展

不算 bug，是需要在文档里明确"划界"：

- **`scripts/`**：Claude Code 规范里，这些文件由模型自己判断，通过 Bash 工具按需执行；现状 `ScriptTool.from()` 在加载阶段就自动把每个脚本转成一个独立具名 Tool，模型直接 function-call 调用，不经过 Bash——这是 j-langchain 的增值封装（更省心），不是规范行为，但也不冲突，保留现状。
- **`agents/`**：Claude Code 里 Skill 和 SubAgent 是两个独立顶层概念，SKILL.md 目录不会有 `agents/` 子目录。现状"技能内嵌子代理"是 j-langchain 自己的设计，遇到真实的 Claude Code Skill 包（没有这个目录）也不影响加载（`loadAgents()` 找不到目录会安全返回空列表）。

**方案**：不改代码，只在 `skill.md` 里加一句标注"以下为 j-langchain 扩展，非 Claude Code 规范组成部分"。

### 2.4 遗漏的标准可选字段（`license` / `metadata`）

`license`、`metadata` 是 Claude Code SKILL.md 里常见的可选前言字段。现状 loader 只挑认识的 key，其余静默忽略——不算 bug，但读到了也没地方存,以后想做"技能来源审计"之类的功能会缺数据。

**方案**：`SkillConfig` 新增 `license`（String，可空）、`metadata`（`Map<String,Object>`，可空）两个透传字段，loader 顺手解析，不使用不影响任何现有行为。

### 2.5 暂不处理的点

- **统一调度工具 / 多目录发现**：即"很多个 Skill 怎样被主 Agent 统一管理、按目录扫描合并、路由调用"——这属于更上层的架构范畴，会在别处单独设计，本文档不涉及。
- **`disable-model-invocation`（仅手动触发的技能）**：Claude Code 里部分 Skill 标记为"不允许模型自主调用，只能显式触发"。目前没有对应场景需求，不做。
- **`scripts-mode: raw`**（脚本不自动转 Tool，改为纯文件 + 模型自己用 Bash 调）：现状的自动转 Tool 是增强而非缺陷，不做。

---

## 3. 兼容性矩阵（本次实际要改的项）

| 配置项 | 位置 | 默认值 | 是否 breaking |
|---|---|---|---|
| `allowed-tools` | SKILL.md frontmatter | — | 无变化，仅补充文档说明 |
| `references-mode` | SKILL.md frontmatter（新增） | `inline`（保留现状） | 否，纯新增可选项 |
| `license` / `metadata` | SKILL.md frontmatter（新增） | 空 | 否，纯新增 |

---

## 4. 核心数据结构变更

```java
public class SkillConfig {
    private String name;
    private String description;
    private List<String> allowedTools;      // 不变：借用父 Agent 工具的白名单

    private String systemPrompt;
    private List<ReferenceDoc> references;  // was: List<String> —— 支持 lazy 模式
    private ReferencesMode referencesMode;  // 新增：INLINE(默认) | LAZY

    private List<ScriptDef> scripts;
    private List<SubAgentConfig> agents;
    private Integer maxIterations;

    private String license;                 // 新增，透传
    private Map<String, Object> metadata;   // 新增，透传
}

public record ReferenceDoc(String filename, String content, String summary) {}

public enum ReferencesMode { INLINE, LAZY }
```

`Skill.buildSystemPrompt()` 按 `referencesMode` 分支：

```java
private String buildSystemPrompt() {
    StringBuilder sb = new StringBuilder(config.getSystemPrompt());
    if (config.getReferences() == null || config.getReferences().isEmpty()) return sb.toString();

    if (config.getReferencesMode() == ReferencesMode.LAZY) {
        sb.append("\n\n---\n\nAvailable reference documents (read on demand via read_reference):\n");
        config.getReferences().forEach(r ->
                sb.append("- ").append(r.filename())
                  .append(r.summary() != null ? ": " + r.summary() : "").append("\n"));
        // buildExecutor() 额外注册 read_reference(file) 工具，从 config.getReferences() 里按 filename 查内容返回
    } else {
        sb.append("\n\n---\n\n");
        sb.append(config.getReferences().stream().map(ReferenceDoc::content)
                .collect(Collectors.joining("\n\n---\n\n")));
    }
    return sb.toString();
}
```

---

## 5. 落地步骤

### Step 1：`skill.md` 文档补充说明（无代码变更）
- 补充 §2.1 的设计意图说明（为什么 `allowed-tools` 现状就是对的）。
- 补充 §2.3 的"以下为 j-langchain 扩展"标注（`scripts/`、`agents/`）。

验证：无需跑测试，纯文档 review。

### Step 2：references 渐进式加载
- `SkillConfig.references` 改为 `List<ReferenceDoc>`，新增 `referencesMode`（默认 `INLINE`）。
- loader（`ClasspathSkillConfigLoader`/`FileSystemSkillConfigLoader`）解析新 frontmatter key `references-mode`，读取 `.md` 文件时顺手取首行作为 `summary`。
- `Skill.buildSystemPrompt()` 按上面代码分支处理；`LAZY` 模式下 `buildExecutor()` 额外注册 `read_reference` 工具。
- 验证：新增测试用例，分别对 `inline`/`lazy` 两种配置断言生成的 systemPrompt 内容和是否挂载了 `read_reference` 工具。

```bash
mvn -q -o test -Dtest=SkillTest,ClasspathSkillConfigLoaderTest,FileSystemSkillConfigLoaderTest
```

### Step 3：`license`/`metadata` 透传
- loader 增加两个字段解析，`SkillConfig` 增加对应 getter（Lombok `@Data` 自动生成）。
- 验证：现有测试资源里加一份带 `license`/`metadata` 字段的 SKILL.md（或在测试里内联构造 YAML），断言解析不报错、字段值正确。

---

## 6. 暂不做的事

- 统一调度工具（`skill(name, args)`）与多目录发现——留给上层架构设计。
- 插件命名空间（`plugin:skill` 前缀）——同上，等上层架构确定后再看要不要接。
- `disable-model-invocation`（仅手动触发的技能）——无场景需求，不做。
- `scripts/` 的 `raw` 执行模式——现状自动转 Tool 是增强，不做调整。
- `agents/` 内嵌子代理机制——继续保留，是独立于本次兼容性目标之外的 j-langchain 特色能力。
