# Skill 设计

## 1. 设计背景与目标

随着 LLM 应用从单体 Agent 演进到多 Agent 协作，出现了一个核心问题：**如何把一段复杂的子任务逻辑封装成一个对主 Agent 透明的原子单元？**

原始做法是把所有工具和逻辑都堆在一个 Agent 里，带来三个痛点：

- **可复用性差**：相同的子流程（如"查天气"、"搜航班"）在多个 Agent 中重复实现。
- **系统提示膨胀**：主 Agent 需要同时理解所有子任务的细节，system prompt 越来越长。
- **可测性差**：无法独立测试某段子工作流，只能通过整体运行来验证。

**Skill** 的设计目标是：将一个子任务的完整工作流（system prompt + 工具集 + 知识库 + 可执行脚本）打包为一个独立单元，对主 Agent 暴露为普通 `Tool`，内部运行完整的 Function-Calling 循环。主 Agent 无需感知 Skill 的内部细节，只需给它一个输入，等待最终结果。

---

## 2. 整体架构

```
┌─────────────────────────────────────────────────────────────────┐
│                         主 Agent (Master)                       │
│                                                                 │
│   McpAgentExecutor                                              │
│   ├─ LLM                                                        │
│   ├─ Tool: search_web                                           │
│   ├─ Tool: send_email                                           │
│   └─ Tool: travel_planner  ←── Skill 注册为普通 Tool              │
│              │                                                  │
│              │ asTool()                                         │
│              ▼                                                  │
│        ┌─────────────────────────────────┐                      │
│        │           Skill                 │                      │
│        │  config  (SKILL.md)             │                      │
│        │  ownTools (显式注册)             │                      │
│        │  parentTools (allowedTools 注入) │                      │
│        │         │                       │                      │
│        │         ▼  invoke()             │                      │
│        │  McpAgentExecutor（内部执行器）   │                      │
│        │  ├─ LLM（继承自主 Agent）         │                      │
│        │  ├─ systemPrompt（SKILL.md 内容）│                      │
│        │  ├─ ScriptTools（scripts/*）     │                      │
│        │  ├─ ownTools                    │                      │
│        │  ├─ parentTools（borrowed）      │                      │
│        │  └─ SubAgents（agents/* 嵌入）    │                      │
│        └─────────────────────────────────┘                      │
└─────────────────────────────────────────────────────────────────┘
```

**核心设计理念**：Skill 对外是 Tool，对内是完整的 Agent。主 Agent 的工具调用协议与 Skill 内部执行协议完全解耦。

---

## 3. SKILL.md 目录结构

Skill 使用约定优于配置的文件布局：

```
skills/travel-planner/
  SKILL.md              ← 前言（name/description/allowed-tools）+ 系统提示正文
  references/           ← 注入系统提示的领域知识文档
    destinations.md
    booking-policy.md
  scripts/              ← 可执行脚本，自动转为 Tool
    get_weather.py
    search_flight.sh
  agents/               ← 嵌入式子代理，注册为内部 Tool
    budget-advisor.md
```

### SKILL.md 格式

```markdown
---
name: travel_planner
description: 旅行规划专家。当用户需要制定旅行计划时调用。
allowed-tools:
  - search_hotel
  - book_ticket
max-iterations: 8
---

你是一位资深旅行规划专家。根据用户需求，制定完整的行程方案。

工作流程：
1. 查询目的地天气
2. 搜索航班和酒店
3. 估算旅行预算
4. 输出结构化行程单
```

| 前言字段 | 类型 | 说明 |
|---------|------|------|
| `name` | String | Skill 标识符，也是 Tool 名称 |
| `description` | String | 主 LLM 看到的 Tool 描述，影响路由决策 |
| `allowed-tools` | List | 允许从父 Agent 借用的工具名称列表 |
| `max-iterations` | Integer | 内部执行器最大迭代次数，默认 10 |
| `license` | String | 可选，来源/授权声明，仅透传存储，不参与执行逻辑 |
| `metadata` | Map | 可选，任意附加信息，仅透传存储 |

> **与 Claude Code 语义对齐说明**：Claude Code 的 `allowed-tools` 限制的是"技能运行期间能用主 Agent 的哪些工具"——因为 Claude Code 的技能就运行在主 Agent 会话里，内置工具本质上就是主 Agent 自己的工具，没有第二个工具来源。j-langchain 的 Skill 工具来源分三层（见 §5），其中脚本工具和自有工具是 Skill 作者构建时显式挑选的封闭小集合，天然不需要再收紧；唯一开放的、可能很大的外部工具面就是父 Agent 的工具（`parentTools`）。所以 `allowed-tools` 只过滤 `parentTools` 这一层、决定借用父 Agent 哪些工具，和 Claude Code "限制技能可用主 Agent 工具范围"其实是同一件事，只是用"白名单借用"的方式实现了同样的收紧效果——两者语义一致，不需要拆分成"借用"和"收紧"两个字段。

---

## 4. 核心数据结构

### SkillConfig

`SkillConfig` 是 Skill 的纯数据配置对象，是加载器（`ClasspathSkillConfigLoader`）的输出，也是 `Skill.from()` 的输入：

```java
public class SkillConfig {
    private String name;
    private String description;
    private List<String> allowedTools;      // 允许借用的父工具
    private String systemPrompt;             // SKILL.md 正文
    private List<ReferenceDoc> references;   // references/*.md，内容按 referencesMode 决定是否预加载
    private ReferencesMode referencesMode;   // INLINE(默认) | LAZY
    private List<ScriptDef> scripts;         // scripts/* 脚本定义
    private List<SubAgentConfig> agents;     // agents/*.md 嵌入式子代理
    private Integer maxIterations;
    private String license;                  // 可选，透传
    private Map<String, Object> metadata;    // 可选，透传
}

public record ReferenceDoc(String filename, String content, String summary) {}

public enum ReferencesMode { INLINE, LAZY }
```

`SkillConfig` 与存储介质无关——同一配置结构既可从 classpath 加载（`ClasspathSkillConfigLoader`），也可从数据库或代码构造。

### ScriptDef

```java
public class ScriptDef {
    private String name;     // 工具名（文件名去掉扩展名）
    private String type;     // 扩展名：py / sh / js / rb
    private String content;  // 脚本源代码
}
```

---

## 5. 工具来源三层体系

Skill 内部执行器的工具集由三个来源合并：

```
优先级  来源              注册方式
────────────────────────────────────────────────────────
1      ScriptTools       SKILL.md scripts/* 自动转换
2      ownTools          Skill.Builder.tools(...) 显式注册
3      parentTools       McpAgentExecutor 按 allowedTools 注入
```

**ScriptTools（脚本工具，j-langchain 扩展）**

> Claude Code 规范里，`scripts/` 下的文件是普通文件，由模型自己判断，通过 Bash 工具按需执行；j-langchain 在加载阶段就把每个脚本自动转成一个独立具名 Tool，模型直接 function-call 调用，不经过 Bash——这是增值封装，不是规范要求，但完全向下兼容：一个真实的 Claude Code Skill 包里的 `scripts/*` 一样能被自动转成 Tool 使用。

每个脚本文件在 `ScriptTool.from(ScriptDef)` 时被写入系统临时目录，由 `ProcessBuilder` 执行。只有 stdout 被捕获作为工具返回值，脚本源码不进入任何 LLM 上下文：

```
支持扩展名  执行器
────────────────────
.py         python
.sh         bash
.js         node
.rb         ruby
```

自定义执行器注册：

```java
ScriptTool.register("groovy", "groovy");
```

**parentTools（借用工具）**

`allowedTools` 声明了 Skill 希望从主 Agent 借用的工具名白名单。主 Agent 在构建时自动过滤并注入：

```java
// McpAgentExecutor.Builder.build() 内部逻辑
for (Skill skill : skills) {
    List<Tool> allowed = tools.stream()
        .filter(t -> skill.getAllowedTools().contains(t.getName()))
        .toList();
    skill.injectParentTools(allowed);
    tools.add(skill.asTool());
}
```

---

## 6. 知识库注入（references）

`references/*.md` 中的文档支持两种加载模式，由前言字段 `references-mode` 控制：

```yaml
references-mode: inline   # inline(默认) | lazy
```

**`inline`（默认，向下兼容原有行为）**：文档在加载时预读入内存，拼接到系统提示尾部，以 `---` 分隔：

```
[SKILL.md 正文]

---

[references/destinations.md 内容]

---

[references/booking-policy.md 内容]
```

这样 Skill 内部的 LLM 在每次调用时都能"看见"领域知识，而不需要额外的 RAG 检索开销。适合体量小但高度稳定的参考文档（价目表、政策文本、工作流规范等）。

**`lazy`（可选，贴近 Claude Code 的渐进式加载）**：系统提示尾部只拼接一份文件清单（文件名 + 首行摘要），不预加载全文；Skill 内部执行器额外挂载一个 `read_reference(file: String)` 工具，需要时自己按需读取全文：

```
[SKILL.md 正文]

---

Available reference documents (read on demand via read_reference):
- destinations.md: 目的地城市概览与最佳旅行季节
- booking-policy.md: 退改签与预订政策
```

适合体量较大、并非每次调用都用得上的参考文档，避免每次调用都消耗额外 token。

---

## 7. 嵌入式子代理（agents/ 目录，j-langchain 扩展）

> Claude Code 里 Skill 和 SubAgent（`.claude/agents/*.md`）是两个独立的顶层概念，SKILL.md 目录不会有 `agents/` 子目录。以下"技能内嵌子代理"是 j-langchain 自己的设计，不是 Claude Code 规范组成部分；遇到真实的 Claude Code Skill 包（没有这个目录）也不影响加载——`loadAgents()` 找不到目录会安全返回空列表。

Skill 内部可以嵌入轻量的 `SubAgent`，用于进一步的任务分解：

```
skills/travel-planner/
  agents/
    budget-advisor.md   ← 内嵌子代理，可访问 get_hotel_price 工具
```

`budget-advisor.md` 格式（支持前言，也支持无前言纯正文）：

```markdown
---
name: budget_advisor
description: 旅行预算顾问。根据城市名称计算3晚住宿预算。
allowed-tools:
  - get_hotel_price
---

你是旅行预算顾问，专注于住宿费用估算。
收到城市名称后，调用 get_hotel_price 查询酒店均价，按3晚计算总费用。
```

`ClasspathSkillConfigLoader` 在加载时将 `agents/*.md` 解析为 `SubAgentConfig` 列表，`Skill.buildExecutor()` 将每个 `SubAgentConfig` 构建为 `SubAgent` 并通过 `McpAgentExecutor.Builder.subAgent()` 注册：

```
Skill.buildExecutor()
  │
  ├── 合并三类工具 → allTools
  ├── 构建 McpAgentExecutor.Builder
  └── 遍历 config.getAgents()
       └── SubAgent.from(agentConfig, chainActor).llm(skill的llm).build()
           └── builder.subAgent(subAgent)
```

嵌入式子代理的 LLM 默认继承 Skill 自己的 LLM（因为 Skill 本身就只有一个 `llm` 字段）。

---

## 8. Observability（可观测性）

### verbose 模式

`Builder.verbose(true)` 为 Skill 和其嵌入式子代理自动生成带前缀的控制台日志：

```
[skill:travel_planner] LLM input:
你是旅行规划专家...（完整 prompt）
[skill:travel_planner] ToolCall: get_weather {"city":"成都"}
[skill:travel_planner] Observation: 成都：多云，18~26°C

[skill:travel_planner>budget_advisor] ToolCall: get_hotel_price {"city":"成都"}
[skill:travel_planner>budget_advisor] Observation: 三星均价280元/晚
```

嵌入式子代理使用 `[skill:<skillName>><agentName>]` 的二级前缀，与主 Skill 日志视觉上可区分。

### 精细回调

```java
Skill.from(config, chainActor)
    .llm(llm)
    .onLlm(msg -> metricsService.recordPromptTokens(msg))
    .onToolCall(tc -> auditLog.record("tool_call", tc))
    .onObservation(obs -> tracer.span("observation", obs))
    .build();
```

| 回调 | 触发时机 | 参数内容 |
|------|---------|---------|
| `onLlm` | 每次调用 LLM 前 | 完整 prompt 文本 |
| `onToolCall` | 每次工具调用前 | `"{toolName} {argsJson}"` |
| `onObservation` | 每次工具返回后 | 工具返回结果字符串 |

`verbose(true)` 和自定义回调互斥：启用 verbose 后自定义回调被覆盖；若先设置自定义回调后调用 `verbose(false)` 则清除自定义回调。

---

## 9. 停止信号传播

Skill 内部执行时会检查 `ContextBus` 中的父 Agent 停止信号：

```java
// Skill.invoke() 内部
AtomicBoolean parentSignal = ContextBus.get()
    .getTransmit(CallInfo.STOP_SIGNAL.name());
return executor.invoke(input, parentSignal).getText();
```

当主 Agent 被外部停止时，停止信号通过 `ContextBus.transmit` 级联传入 Skill 的内部执行器，Skill 会在下一次工具调用前感知并中止，避免资源泄漏。

---

## 10. 延迟初始化

内部 `McpAgentExecutor` 采用双检锁延迟初始化：

```java
public String invoke(String input) {
    if (executor == null) {
        synchronized (this) {
            if (executor == null) {
                executor = buildExecutor();
            }
        }
    }
    ...
}
```

调用 `injectParentTools()` 时会将 `executor` 置为 `null`，强制下次调用时重建。这保证了工具注入顺序（先注入，再调用）的正确性，同时避免不必要的执行器构建开销。

---

## 11. 包结构

```
core/
  skill/
    SkillConfig.java              # 纯数据配置类
    Skill.java                    # 核心封装，对外暴露 asTool()/invoke()
    ScriptDef.java                # 脚本定义（名称/类型/源码）
    ScriptTool.java               # 脚本 → Tool 转换器（ProcessBuilder 执行）
    loader/
      SkillConfigLoader.java      # 加载器接口
      ClasspathSkillConfigLoader.java  # Classpath 实现，解析 SKILL.md 目录
```

---

## 12. 使用示例

### 从 classpath 加载

```java
// 加载 resources/skills/travel-planner/ 目录
SkillConfig config = ClasspathSkillConfigLoader.fromClasspath("skills/travel-planner");

Skill travelPlanner = Skill.from(config, chainActor)
    .llm(llm)
    .verbose(true)
    .build();

McpAgentExecutor master = McpAgentExecutor.builder(chainActor)
    .llm(llm)
    .tools(searchHotelTool, bookTicketTool)  // travel_planner 通过 allowedTools 借用
    .skill(travelPlanner)
    .build();
```

### 代码构造（无需文件）

```java
SkillConfig config = SkillConfig.builder()
    .name("weather_skill")
    .description("查询城市天气")
    .systemPrompt("你是天气查询专家，收到城市名称后调用工具查询当前天气。")
    .build();

Skill weatherSkill = Skill.from(config, chainActor)
    .llm(llm)
    .tools(getWeatherTool)   // 直接注册工具，无需 allowedTools
    .build();
```

---

## 13. 设计原则总结

| 原则 | 体现 |
|------|-----|
| **封装即接口** | Skill 对主 Agent 只暴露 `Tool`，内部实现完全透明 |
| **约定优于配置** | `SKILL.md` 目录布局（references/scripts/agents）有明确的加载约定 |
| **工具来源三层** | 脚本工具/显式工具/借用工具分层清晰，互不干扰 |
| **延迟初始化** | 内部执行器在首次 invoke 时构建，支持构建后注入 |
| **可观测性分级** | verbose 适合开发调试，精细回调适合生产监控 |
| **信号级联** | 停止信号通过 ContextBus 从主 Agent 级联到 Skill 内部，避免资源泄漏 |
