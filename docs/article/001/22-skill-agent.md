# Skill Agent：把子工作流封装成可复用的 Tool

> **标签**：`Java` `Skill` `SkillAgent` `技能封装` `子工作流` `McpAgentExecutor` `j-langchain`  
> **前置阅读**：[MCP React Agent：工具调用的标准范式](11-mcp-react-agent.md)  
> **适合人群**：已掌握 `McpAgentExecutor` 基础用法，希望构建可复用子流程的 Java 开发者

---

## 一、问题：单 Agent 越堆越重

随着业务复杂度增长，Agent 开发者通常会遇到三个痛点：

**可复用性差**：同一段子流程（查天气 + 查机票 + 查酒店）在多个 Agent 里反复实现，修改一处需要改多处。

**系统提示膨胀**：主 Agent 需要同时理解所有子任务的详细工作流，`systemPrompt` 越来越长，路由越来越混乱。

**可测性差**：子工作流嵌在主 Agent 里无法单独跑，每次验证都要触发完整流程。

**Skill** 正是为解决这三个问题而设计的：将一个子任务的完整工作流（systemPrompt + 工具集 + 知识库）打包成一个独立单元，对主 Agent 暴露为普通 `Tool`，主 Agent 只需传入输入、等待输出，完全不感知内部细节。

---

## 二、整体架构

```
┌───────────────────────────────────────────────────────────┐
│                      主 Agent (Master)                    │
│                                                           │
│   McpAgentExecutor                                        │
│   ├─ LLM                                                  │
│   ├─ Tool: get_weather       ← 普通工具                    │
│   ├─ Tool: get_flight_price  ← 普通工具                    │
│   ├─ Tool: get_hotel_price   ← 普通工具                    │
│   └─ Tool: travel_planner    ← Skill 注册为普通 Tool       │ 
│                │                                          │
│                │ asTool()                                 │
│                ▼                                          │
│         ┌─────────────────────────────────┐               │
│         │            Skill                │               │
│         │  config (SKILL.md)              │               │
│         │  ownTools（显式注册）             │               │
│         │  parentTools（allowedTools 注入）│               │
│         │         │                       │               │
│         │         ▼  invoke()             │               │
│         │  McpAgentExecutor（内部执行器）    │              │
│         │  ├─ LLM（继承自主 Agent）         │               │
│         │  ├─ systemPrompt（SKILL.md）     │               │
│         │  ├─ ownTools                    │               │
│         │  └─ parentTools（borrowed）      │               │
│         └─────────────────────────────────┘               │
└───────────────────────────────────────────────────────────┘
```

核心设计理念：**Skill 对外是 Tool，对内是完整的 Agent**。主 Agent 的工具调用协议与 Skill 内部执行协议完全解耦。

---

## 三、SKILL.md：技能的声明文件

Skill 使用目录约定加载配置，classpath 下的目录结构如下：

```
skills/travel-planner/
  SKILL.md              ← 前言（name/description/allowed-tools）+ 系统提示正文
  references/           ← 注入系统提示的领域知识文档（可选）
    city-tips.md
  agents/               ← 嵌入式子代理（可选）
    budget-advisor.md
```

`SKILL.md` 的格式是带 YAML 前言的 Markdown：

```markdown
---
name: travel_planner
description: 旅行规划技能。查询目的地天气、机票价格、酒店均价，综合输出旅行建议。
             当用户询问旅行计划、出行安排、城市推荐时使用。
allowed-tools:
  - get_weather
  - get_flight_price
  - get_hotel_price
max-iterations: 15
---

# 旅行规划工作流

你是专业旅行规划助手，负责为用户提供全面的出行建议。

## Phase 1：解析目的地
从用户输入中提取所有目的地城市。

## Phase 2：并行收集信息
对每个目的地依次执行：
1. 调用 get_weather 查询天气
2. 调用 get_flight_price 查询机票
3. 调用 get_hotel_price 查询酒店均价

## Phase 3：综合输出建议
整合信息，输出天气概况、价格对比、综合推荐和预算估算。
```

| 前言字段 | 说明 |
|---------|------|
| `name` | Skill 标识符，也是主 Agent 看到的 Tool 名称 |
| `description` | 主 LLM 的路由依据，写得越准确，主 Agent 调用越精准 |
| `allowed-tools` | 允许从父 Agent 借用的工具名白名单 |
| `max-iterations` | 内部执行器最大迭代次数，默认 10 |

---

## 四、模式一：classpath SKILL.md + Master Agent（工具借用）

最常见的用法：技能配置从 classpath 加载，工具由主 Agent 持有，Skill 通过 `allowed-tools` 声明想借哪些。

```java
TravelTools tools = new TravelTools();
Tool weatherTool = buildTool("get_weather",      "查询城市天气", "city: String", tools::getWeather);
Tool flightTool  = buildTool("get_flight_price", "查询机票价格", "city: String", tools::getFlightPrice);
Tool hotelTool   = buildTool("get_hotel_price",  "查询酒店均价", "city: String", tools::getHotelPrice);

var llm = ChatAliyun.builder().model("qwen-plus").temperature(0f).build();

// 从 classpath resources/skills/travel-planner/ 加载 SKILL.md
SkillConfig config = ClasspathSkillConfigLoader.fromClasspath("skills/travel-planner");
Skill travelSkill = Skill.from(config, chainActor).llm(llm).verbose(true).build();

// 主 Agent 持有全部工具，Skill 在 build() 时自动注入 allowed-tools 里的工具
McpAgentExecutor master = McpAgentExecutor.builder(chainActor)
        .llm(ChatAliyun.builder().model("qwen-plus").temperature(0f).build())
        .tools(weatherTool, flightTool, hotelTool)
        .skill(travelSkill)
        .systemPrompt("你是旅行规划总助手，遇到旅行规划任务请使用 travel_planner 技能。")
        .onToolCall(tc  -> System.out.println("[ToolCall] " + tc))
        .onObservation(obs -> System.out.println("[Observation] " + obs))
        .build();

String result = master.invoke("我想从上海出发，去成都和西安旅游，帮我规划一下").getText();
```

**工具借用机制**：`master.build()` 内部遍历已注册的 Skill，按 `allowedTools` 白名单过滤主 Agent 的工具列表，注入到 Skill 的内部执行器。工具对象只有一份，Skill 和主 Agent 共享引用，不会重复构建。

```
主 Agent 工具：[get_weather, get_flight_price, get_hotel_price]
                                     ↓ allowedTools 过滤
Skill 内部工具：[get_weather, get_flight_price, get_hotel_price]  ← 全部符合白名单
```

执行时日志（开启 `verbose(true)`）：

```
[skill:travel_planner] ToolCall: get_weather {"city":"成都"}
[skill:travel_planner] Observation: 成都：多云，18~26°C，下午有小雨
[skill:travel_planner] ToolCall: get_flight_price {"city":"成都"}
[skill:travel_planner] Observation: 上海→成都：¥980（经济舱）
...

========== 旅行规划结果 ==========
**成都**
- 天气：多云，18~26°C，下午有小雨，建议携带雨具
- 机票：上海→成都 ¥980（经济舱）
- 酒店：三星 ¥280/晚，四星 ¥520/晚
- 3晚预算：¥980 + ¥840（三星）= 约 ¥1820 起
...
```

---

## 五、模式二：代码直接构造 SkillConfig（无文件依赖）

不想依赖 classpath 文件，或在单元测试里快速构造 Skill，可以直接在代码里组装 `SkillConfig`：

```java
SkillConfig config = SkillConfig.builder()
        .name("weather_flight_query")
        .description("查询目的地天气和机票信息")
        .allowedTools(List.of("get_weather", "get_flight_price"))
        .systemPrompt("""
                你是出行信息助手。
                收到城市名后，依次调用 get_weather 和 get_flight_price，
                最后整合结果输出简洁的出行参考。
                """)
        .build();

var llm = ChatAliyun.builder().model("qwen-plus").temperature(0f).build();
Skill skill = Skill.from(config, chainActor).llm(llm).build();

McpAgentExecutor master = McpAgentExecutor.builder(chainActor)
        .llm(ChatAliyun.builder().model("qwen-plus").temperature(0f).build())
        .tools(weatherTool, flightTool)
        .skill(skill)
        .build();

String result = master.invoke("我打算去桂林，帮我查一下天气和机票").getText();
```

`SkillConfig` 与存储介质完全无关——同一配置结构可以从 classpath 加载，也可以从数据库读出，或像这里一样在代码里直接构造，三种来源对 `Skill.from()` 完全透明。

---

## 六、模式三：Skill 独立运行（脱离 Master Agent）

Skill 不一定要挂在主 Agent 上。如果子任务逻辑完整、不需要主 Agent 参与路由，可以直接调用 `skill.invoke()`：

```java
SkillConfig config = ClasspathSkillConfigLoader.fromClasspath("skills/travel-planner");
Skill skill = Skill.from(config, chainActor)
        .llm(ChatAliyun.builder().model("qwen-plus").temperature(0f).build())
        .tools(weatherTool, flightTool, hotelTool)   // 独立运行时工具直接注册，无需 allowedTools
        .onToolCall(tc  -> System.out.println("[ToolCall]    " + tc))
        .onObservation(obs -> System.out.println("[Observation] " + obs))
        .build();

String result = skill.invoke("我想去三亚旅游，出发地上海");
System.out.println(result);
```

独立运行时工具通过 `.tools()` 直接注册，不走 `allowedTools` 借用流程。这种模式常见于：
- 批量脚本任务（不需要主 Agent 做任务分发）
- 集成测试（直接验证 Skill 的工作流逻辑，排除主 Agent 干扰）
- 微服务入口（Skill 本身即服务边界）

---

## 七、三种模式的选择依据

| 场景 | 推荐模式 | 原因 |
|------|---------|------|
| 主 Agent 需要路由到多个子任务 | classpath + Master | 主 Agent 统一持有工具，按需分配 |
| 快速原型 / 单元测试 | 代码构造 SkillConfig | 无文件依赖，改配置即改代码 |
| 子任务完整独立、无需路由 | Skill 独立运行 | 最简路径，直接 `skill.invoke()` |

---

## 八、运行前置条件

1. **`ALIYUN_KEY`** 环境变量：示例使用 `qwen-plus`
2. classpath 模式需在 `src/test/resources/` 下放置 `skills/travel-planner/SKILL.md`
3. 代码构造和独立运行模式无文件依赖

---

## 九、总结

Skill 解决的核心问题是**子工作流的封装与复用**：

- **对外统一接口**：Skill 对主 Agent 暴露为普通 `Tool`，主 Agent 无需感知内部细节
- **工具来源分层**：脚本工具（`scripts/`）、显式注册工具（`.tools()`）、借用工具（`allowedTools`）三层合并，互不干扰
- **配置与实现解耦**：`SkillConfig` 与存储介质无关，classpath / 数据库 / 代码构造三种来源等价
- **三种运行模式**：classpath + Master（生产首选）、代码构造（测试友好）、独立运行（完整子任务），按需选择

一个设计良好的 Skill 就像一个接口稳定的微服务——主 Agent 只需要知道"调什么"，Skill 负责"怎么做"。

---

> 📎 相关资源
> - 完整代码：[Article22SkillAgent.java](../../../src/test/java/org/salt/jlangchain/demo/article/Article22SkillAgent.java)
> - 技能配置：[skills/travel-planner/SKILL.md](../../../src/test/resources/skills/travel-planner/SKILL.md)
> - j-langchain GitHub：https://github.com/flower-trees/j-langchain
> - 运行环境：`ALIYUN_KEY`（`qwen-plus`）
