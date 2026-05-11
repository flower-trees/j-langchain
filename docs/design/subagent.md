# SubAgent 设计

## 1. 设计背景与目标

在多 Agent 系统中，任务分解有两种常见模式：

**Skill 模式**：子任务逻辑固定、工具集封闭，适合"查天气""搜航班"这类标准化子流程。Skill 借用父 Agent 的工具，但不拥有自己的工具。

**SubAgent 模式**：子任务需要更高自主性——有自己的工具集、可以使用不同的模型、甚至可以递归地调用下一层的 Skill。SubAgent 适合"旅行研究员"这类需要多步骤推理和专属工具的角色。

两者的核心区别：

| 维度 | Skill | SubAgent |
|------|-------|----------|
| 工具来源 | 脚本工具 + 借用父工具 | 自有工具 + 可选借用父工具 |
| LLM | 继承主 Agent LLM | 可继承、可指定模型名、可显式注入 |
| 知识注入 | references/ 文档拼入 system prompt | 关联 Skill 的知识拼入 system prompt |
| 内部可组合性 | 可嵌入 SubAgent（agents/） | 可调用 Skill（作为 Tool）|
| 典型场景 | 标准化子流程 | 自主子代理、专业领域代理 |

**SubAgent 的设计目标**：提供比 Skill 更高自主度的子代理封装，支持灵活的 LLM 配置策略，同时保持与主 Agent 相同的 `Tool` 接口，实现无缝嵌套。

---

## 2. 整体架构

```
┌─────────────────────────────────────────────────────────────────┐
│                         主 Agent (Master)                       │
│                                                                 │
│   McpAgentExecutor                                              │
│   ├─ LLM (master LLM)                                           │
│   ├─ Tool: book_flight                                          │
│   └─ Tool: travel_researcher  ←── SubAgent 注册为普通 Tool        │
│              │                                                  │
│              │ asTool()                                         │
│              ▼                                                  │
│   ┌──────────────────────────────────────────────────┐          │
│   │                    SubAgent                      │          │
│   │  config (AGENT.md)                               │          │
│   │  ownTools (显式注册)                              │          │
│   │  inheritedTools (allowedTools 注入)               │          │
│   │  callableSkills (Skills 作为内部 Tool)            │          │
│   │           │                                      │          │
│   │           ▼  invoke()                            │          │
│   │  McpAgentExecutor（内部执行器）                    │          │
│   │  ├─ LLM（resolveLlm 三级优先）                     │          │
│   │  ├─ systemPrompt（AGENT.md + Skill 知识）         │          │
│   │  ├─ ownTools                                     │          │
│   │  ├─ inheritedTools（borrowed）                    │          │
│   │  └─ Skill Tools（callableSkills）                 │          │
│   └──────────────────────────────────────────────────┘          │
└─────────────────────────────────────────────────────────────────┘
```

---

## 3. AGENT.md 目录结构

```
agents/travel-researcher/
  AGENT.md              ← 前言（name/description/model/tools/skills）+ 系统提示正文
```

与 Skill 不同，SubAgent 目录结构更简单——没有 `references/`、`scripts/`、`agents/` 子目录。所有扩展能力（知识、工具、子流程）通过配置字段引用外部的 Skill 或 Tool 来实现，而不是内嵌在目录里。

### AGENT.md 格式

```markdown
---
name: travel_researcher
description: 旅行研究员。综合查询天气、航班、酒店，输出旅行报告。
model: inherit
tools:
  - get_weather
  - search_flight
skills:
  - skills/travel-planner
max-iterations: 15
---

你是旅行研究员，负责全面的旅行信息收集与分析。

收到目的地后：
1. 查询天气状况
2. 搜索可用航班
3. 参考旅行规划技能的最佳实践
4. 输出结构化的旅行报告
```

| 前言字段 | 类型 | 说明 |
|---------|------|------|
| `name` | String | SubAgent 标识符，也是 Tool 名称 |
| `description` | String | 主 LLM 看到的 Tool 描述 |
| `model` | String | LLM 配置：`inherit` / 模型名（如 `qwen-plus`）/ 空 |
| `tools` | List | 允许从父 Agent 借用的工具名白名单 |
| `skills` | List | 需要注入知识的 Skill 目录路径列表 |
| `max-iterations` | Integer | 内部执行器最大迭代次数，默认 10 |

---

## 4. 核心数据结构

### SubAgentConfig

`SubAgentConfig` 是 SubAgent 的纯数据配置对象：

```java
public class SubAgentConfig {
    private String name;
    private String description;
    private String model;              // inherit / 模型名 / null
    private List<String> allowedTools; // 借用父工具的白名单（对应 tools 前言字段）
    private String systemPrompt;       // AGENT.md 正文
    private List<SkillConfig> skills;  // 预加载的 Skill 配置，知识注入 systemPrompt
    private Integer maxIterations;

    public boolean isInheritModel() {
        return "inherit".equalsIgnoreCase(model);
    }
}
```

`skills` 字段存储的是已加载的 `SkillConfig` 对象（非路径），在 `ClasspathSubAgentConfigLoader` 内部完成从路径到配置的转换。SubAgent 使用 Skill 的知识（system prompt + references），但不调用 Skill 的内部执行器——知识是静态注入的。若需要 SubAgent 动态调用 Skill 工作流，需在代码中通过 `Builder.skill()` 注册。

---

## 5. LLM 三级解析优先链

SubAgent 的 LLM 配置是其最核心的设计点，支持三种场景：

```
优先级  场景                    配置方式
────────────────────────────────────────────────────────────────
1      显式注入：开发者已知 LLM   SubAgent.Builder.llm(llm)
2      继承父 LLM：model=inherit  McpAgentExecutor 自动调用 injectLlm(masterLlm)
3      模型工厂：model=qwen-plus  McpAgentExecutor 调用 injectLlmFactory(factory)
                                  → factory.apply("qwen-plus") 在首次 invoke 前执行
```

`resolveLlm()` 的实现：

```java
private BaseChatModel resolveLlm() {
    if (llm != null) return llm;                        // 最高优先：显式设置
    if (injectedLlm != null) return injectedLlm;        // 次优：inherit 或 factory 已解析
    if (llmFactory != null && config.getModel() != null
            && !config.isInheritModel()) {
        return llmFactory.apply(config.getModel());     // 最低：按模型名工厂创建
    }
    return null;
}
```

**主 Agent 注入逻辑**（`McpAgentExecutor.Builder.build()` 内）：

```java
for (SubAgent subAgent : subAgents) {
    if (subAgent.isInheritModel()) {
        subAgent.injectLlm(llm);               // 直接注入主 Agent 的 LLM 实例
    } else if (llmFactory != null) {
        subAgent.injectLlmFactory(llmFactory); // 注入工厂，子代理自己按 model 名构建
    }
    ...
    tools.add(subAgent.asTool());
}
```

### 三种场景举例

**场景 A：显式 LLM（模型固定）**
```java
SubAgentConfig config = SubAgentConfig.builder()
    .name("weather_checker").systemPrompt("...").build();
SubAgent agent = SubAgent.from(config, chainActor).llm(openAiLlm).build();
```

**场景 B：model=inherit（共享主 Agent LLM）**
```markdown
# AGENT.md frontmatter
model: inherit
```
```java
// 主 Agent build() 时自动注入：subAgent.injectLlm(masterLlm)
McpAgentExecutor.builder(chainActor).llm(masterLlm).subAgent(agent).build();
```

**场景 C：model=qwen-plus（通过工厂按名创建）**
```markdown
# AGENT.md frontmatter
model: qwen-plus
```
```java
McpAgentExecutor.builder(chainActor)
    .llm(masterLlm)
    .llmFactory(modelName -> LlmRegistry.get(modelName))  // 工厂注册在主 Agent
    .subAgent(agent)
    .build();
```

---

## 6. 工具来源两层体系

SubAgent 的工具集由两个来源合并（不支持脚本工具，以保持 SubAgent 的纯 Java 性质）：

```
来源              注册方式
────────────────────────────────────────────────────────
ownTools          SubAgent.Builder.tools(...) 显式注册
inheritedTools    McpAgentExecutor 按 allowedTools 注入
```

**allowedTools 注入流程**：

```java
// McpAgentExecutor.Builder.build() 内
if (!subAgent.getAllowedTools().isEmpty()) {
    List<Tool> allowed = tools.stream()
        .filter(t -> subAgent.getAllowedTools().contains(t.getName()))
        .toList();
    subAgent.injectParentTools(allowed);
}
```

`allowedTools` 是白名单机制：SubAgent 声明它需要哪些父工具，主 Agent 只注入匹配的部分，保证最小权限原则。

---

## 7. Skill 知识注入 vs Skill 工具注册

SubAgent 支持两种与 Skill 交互的方式，目的和效果完全不同：

**知识注入（AGENT.md `skills` 字段）**

```markdown
skills:
  - skills/travel-planner
```

`ClasspathSubAgentConfigLoader` 将 Skill 的 `systemPrompt` 和 `references` 内容拼接到 SubAgent 的 system prompt：

```
[SubAgent 自身的 systemPrompt]

---

## Skill Reference: travel_planner

[travel_planner 的 systemPrompt]

[travel_planner 的 references 内容]
```

SubAgent 的 LLM"知道"这些工作流规范，但并不真正调用 Skill 的执行器。适合让 SubAgent 了解协作约定、输出格式规范等静态知识。

**工具注册（代码层面）**

```java
Skill travelSkill = Skill.from(skillConfig, chainActor).llm(llm).build();

SubAgent researcher = SubAgent.from(agentConfig, chainActor)
    .llm(llm)
    .skill(travelSkill)   // Skill 被包装为 Tool 注册进 SubAgent 的执行器
    .build();
```

SubAgent 可以在运行时真正调用 Skill，Skill 内部运行独立的 Function-Calling 循环。适合需要递归委托子任务的场景。

两种方式可同时使用：SubAgent 既"了解"Skill 的工作规范（知识注入），又能"调用"Skill 完成具体子任务（工具注册）。

---

## 8. 在 Skill 内部嵌入 SubAgent（agents/ 目录）

Skill 支持在 `agents/` 子目录内嵌轻量 SubAgent，作为 Skill 内部执行器的工具：

```
skills/travel-planner/
  agents/
    budget-advisor.md
```

`budget-advisor.md` 是一个简化的 SubAgent 配置（`allowed-tools` 字段用于借用 Skill 自身工具集中的工具）：

```markdown
---
name: budget_advisor
description: 旅行预算顾问，计算3晚住宿费用。
allowed-tools:
  - get_hotel_price
---

你是旅行预算顾问，专注住宿费用估算...
```

`ClasspathSkillConfigLoader.loadAgents()` 将 `agents/*.md` 解析为 `SubAgentConfig`，存入 `SkillConfig.agents`。`Skill.buildExecutor()` 遍历 `agents` 列表，为每个 `SubAgentConfig` 构建 `SubAgent` 并注册：

```
Skill.buildExecutor()
  │
  ├── 合并三类工具 → allTools
  ├── 初始化 McpAgentExecutor.Builder
  └── 遍历 config.getAgents()
       ├── SubAgent.from(agentConfig, chainActor)
       │     .llm(skill的llm)
       │     .[verbose 回调或自定义回调]
       │     .build()
       └── builder.subAgent(subAgent)   ← SubAgent 在内部执行器注册
```

嵌入式 SubAgent 的 `allowed-tools` 引用的是 Skill 自身工具集（allTools）中的工具，而非主 Agent 的工具——借用范围限定在 Skill 内部。

---

## 9. Observability（可观测性）

### verbose 模式

```java
SubAgent.from(config, chainActor)
    .llm(llm)
    .verbose(true)
    .build();
```

输出前缀为 `[subagent:<name>]`：

```
[subagent:travel_researcher] LLM input:
你是旅行研究员...（完整 prompt）
[subagent:travel_researcher] ToolCall: get_weather {"city":"成都"}
[subagent:travel_researcher] Observation: 成都：多云，18~26°C
```

当 SubAgent 嵌入在 Skill 内部时（`agents/` 目录），若 Skill 开启了 `verbose(true)`，嵌入式 SubAgent 使用二级前缀：

```
[skill:travel_planner>budget_advisor] ToolCall: get_hotel_price {"city":"成都"}
[skill:travel_planner>budget_advisor] Observation: 三星均价280元/晚
```

### 精细回调

```java
SubAgent.from(config, chainActor)
    .llm(llm)
    .onLlm(msg -> tracer.recordPrompt(msg))
    .onToolCall(tc -> auditLog.record(tc))
    .onObservation(obs -> metricsService.recordObservation(obs))
    .build();
```

---

## 10. 停止信号传播

与 Skill 相同，SubAgent 的 `invoke()` 从 `ContextBus` 读取父 Agent 的停止信号，级联传入内部执行器：

```java
public String invoke(String input) {
    ...
    AtomicBoolean parentSignal = ContextBus.get()
        .getTransmit(CallInfo.STOP_SIGNAL.name());
    return executor.invoke(input, parentSignal).getText();
}
```

停止信号可以从主 Agent → SubAgent → SubAgent 内部的 Skill 层层传递，保证整个调用链能同步停止。

---

## 11. 延迟初始化与注入顺序

SubAgent 的内部执行器同样采用双检锁延迟初始化。三个注入操作（`injectLlm`/`injectLlmFactory`/`injectParentTools`）均会将 `executor` 置为 `null`，确保注入后首次 `invoke()` 时重建执行器：

```
SubAgent 构建
  │
  │ [可能的注入]
  ├─ injectLlm() → executor = null
  ├─ injectLlmFactory() → executor = null (仅当 llm==null && !inherit)
  └─ injectParentTools() → executor = null
                │
                ▼  首次 invoke()
           buildExecutor()
           ├── resolveLlm()       → 三级优先链
           ├── collectTools()     → ownTools + inheritedTools
           ├── buildSystemPrompt() → systemPrompt + Skill 知识拼接
           └── McpAgentExecutor.builder(...).build()
```

---

## 12. 包结构

```
core/
  subagent/
    SubAgentConfig.java                 # 纯数据配置类
    SubAgent.java                       # 核心封装，对外暴露 asTool()/invoke()
    loader/
      SubAgentConfigLoader.java         # 加载器接口
      ClasspathSubAgentConfigLoader.java  # Classpath 实现，解析 AGENT.md
```

`ClasspathSubAgentConfigLoader` 在加载 `skills` 字段时会委托调用 `ClasspathSkillConfigLoader.fromClasspath()`，形成加载器之间的协作。

---

## 13. 与 McpAgentExecutor 的集成

`McpAgentExecutor.Builder` 暴露三个 SubAgent 相关方法：

```java
McpAgentExecutor.builder(chainActor)
    .llm(masterLlm)
    .tools(bookFlightTool, searchHotelTool)
    .subAgent(researcher)          // 注册单个 SubAgent
    .subAgents(analyst, planner)   // 批量注册
    .llmFactory(modelName -> ...)  // 为 model=<name> 的 SubAgent 提供工厂
    .build();
```

`build()` 内部的注册逻辑保证注入顺序正确：先注入 LLM，再注入 parentTools，最后调用 `asTool()` 将 SubAgent 加入工具列表。

---

## 14. 使用示例

### 从 classpath 加载（model=inherit）

```java
SubAgentConfig config = ClasspathSubAgentConfigLoader.fromClasspath("agents/weather-checker");
// AGENT.md frontmatter: model: inherit, tools: [get_weather]

SubAgent weatherChecker = SubAgent.from(config, chainActor).build();

McpAgentExecutor master = McpAgentExecutor.builder(chainActor)
    .llm(masterLlm)
    .tools(getWeatherTool)          // weather_checker 通过 tools 前言借用
    .subAgent(weatherChecker)       // master build() 时自动注入 masterLlm
    .build();
```

### 带 Skill 知识的 SubAgent

```java
SubAgentConfig config = ClasspathSubAgentConfigLoader.fromClasspath("agents/travel-researcher");
// AGENT.md: skills: [skills/travel-planner] ← 知识注入，非工具调用

SubAgent researcher = SubAgent.from(config, chainActor)
    .llm(llm)
    .tools(getWeatherTool, searchFlightTool)
    .verbose(true)
    .build();

McpAgentExecutor master = McpAgentExecutor.builder(chainActor)
    .llm(masterLlm)
    .subAgent(researcher)
    .build();
```

### 代码构造 + 模型工厂

```java
SubAgentConfig config = SubAgentConfig.builder()
    .name("analyst")
    .model("qwen-plus")
    .systemPrompt("你是数据分析师...")
    .build();

SubAgent analyst = SubAgent.from(config, chainActor)
    // 不设置 llm，由 llmFactory 按 model 名解析
    .build();

McpAgentExecutor master = McpAgentExecutor.builder(chainActor)
    .llm(defaultLlm)
    .llmFactory(name -> LlmRegistry.getOrCreate(name))
    .subAgent(analyst)
    .build();
```

---

## 15. 设计原则总结

| 原则 | 体现 |
|------|-----|
| **统一接口** | SubAgent 对主 Agent 只暴露 `Tool`，与普通工具无区别 |
| **LLM 解耦** | 三级 LLM 优先链覆盖显式/继承/工厂三种部署场景 |
| **最小权限** | `allowedTools` 白名单确保 SubAgent 只能访问明确声明的父工具 |
| **知识与调用分离** | Skill 知识静态注入（系统提示）与 Skill 工具动态调用是两个正交能力 |
| **延迟初始化** | 执行器在注入完成后首次 invoke 时构建，注入操作可任意顺序 |
| **信号级联** | 停止信号通过 ContextBus 从主 Agent 级联至 SubAgent，支持多层传播 |
| **可观测性分级** | verbose 开发模式与精细回调生产模式并存，verbose 在 Skill 内嵌时自动加二级前缀 |
