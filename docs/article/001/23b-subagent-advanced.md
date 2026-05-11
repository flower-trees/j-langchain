# SubAgent 进阶：LLM 策略、工具借用与 Skill 嵌套

> **标签**：`Java` `SubAgent` `LLM策略` `llmFactory` `allowedTools` `Skill嵌套` `j-langchain`  
> **前置阅读**：[SubAgent 基础：拥有自主工具的子代理](23a-subagent-basic)  
> **适合人群**：已掌握 SubAgent 基础用法，希望灵活控制模型选择、工具权限与多层嵌套的 Java 开发者

---

## 一、四个进阶场景

上一篇介绍了 SubAgent 的三种基础用法（独立运行、注册到 Master、代码构造）。本篇聚焦四个进阶场景：

| 场景 | 解决的问题 |
|------|-----------|
| `model=inherit` | SubAgent 直接复用主 Agent 的 LLM，无需重复配置 |
| `llmFactory` | SubAgent 在配置文件里声明模型名，运行时按名构建，LLM 实例由主 Agent 统一管理 |
| `allowedTools` | SubAgent 从主 Agent 借用部分工具，遵循最小权限原则 |
| SKILL 内嵌 SubAgent | Skill 的 `agents/` 目录放置轻量 SubAgent，形成两层嵌套 |

---

## 二、LLM 三级解析优先链

理解这四个场景之前，先明确 SubAgent 的 LLM 解析规则。每次 `invoke()` 时，SubAgent 按以下优先级确定使用哪个 LLM：

```
优先级   场景                    配置方式
──────────────────────────────────────────────────────────
1       显式注入（开发者指定）   SubAgent.Builder.llm(llm)
2       inherit（继承主 Agent）  AGENT.md: model: inherit
                               主 Agent build() 时自动调用 injectLlm(masterLlm)
3       模型工厂（按名构建）     AGENT.md: model: qwen-plus
                               主 Agent build() 时调用 injectLlmFactory(factory)
                               首次 invoke() 前 factory.apply("qwen-plus") 执行
```

三个优先级覆盖了所有实际部署场景：**测试环境显式注入**、**同类任务共享主 LLM**、**差异化模型按名管理**。

---

## 三、model=inherit：SubAgent 继承主 Agent 的 LLM

最简单的 LLM 复用方式：SubAgent 不自己配置 LLM，直接用主 Agent 的。

**配置文件**（`agents/weather-checker/AGENT.md`）：

```markdown
---
name: weather_checker
description: 天气查询专员。专注查询城市天气信息，当需要了解目的地天气时使用。
model: inherit
tools:
  - get_weather
max-iterations: 5
---

你是天气查询专员，专注于查询城市当前天气状况。
收到城市名称后，调用 get_weather 工具，返回简洁的天气报告。
```

`model: inherit` 加上 `tools: [get_weather]`（借用父 Agent 的 `get_weather` 工具）。

**代码**：

```java
var masterLlm = ChatAliyun.builder().model("qwen-plus").temperature(0f).build();

SubAgentConfig config = ClasspathSubAgentConfigLoader.fromClasspath("agents/weather-checker");
// model=inherit → 不在 builder 里调用 .llm()，由主 Agent build() 时自动注入
SubAgent weatherAgent = SubAgent.from(config, chainActor)
        .tools(buildTool("get_weather", "查询城市天气", "city: String", tools::getWeather))
        .onToolCall(tc  -> System.out.println("[weather_checker] ToolCall: " + tc))
        .onObservation(obs -> System.out.println("[weather_checker] Observation: " + obs))
        .build();

McpAgentExecutor master = McpAgentExecutor.builder(chainActor)
        .llm(masterLlm)           // 这个 llm 会被自动注入到 model=inherit 的 SubAgent
        .subAgent(weatherAgent)
        .systemPrompt("你是旅行助手，天气查询任务请使用 weather_checker。")
        .build();

String result = master.invoke("成都现在天气怎么样？").getText();
```

主 Agent `build()` 内部逻辑：

```java
for (SubAgent subAgent : subAgents) {
    if (subAgent.isInheritModel()) {
        subAgent.injectLlm(masterLlm);  // 直接注入主 Agent 的 LLM 实例
    }
    ...
}
```

**适用场景**：子任务与主任务性质相同，不需要差异化模型，想减少配置重复。

---

## 四、llmFactory：按模型名动态构建 LLM

当不同 SubAgent 需要使用不同模型时，`llmFactory` 提供了统一的 LLM 管理入口：SubAgent 在配置里声明模型名，主 Agent 提供一个"按名构建"的工厂函数。

```java
// SubAgent 配置：声明模型名，不传 llm
SubAgentConfig config = SubAgentConfig.builder()
        .name("flight_checker")
        .description("机票价格查询专员。当需要查询机票信息时使用。")
        .model("qwen-turbo")    // 由主 Agent 的 llmFactory 解析
        .systemPrompt("你是机票查询专员，收到城市名后调用 get_flight_price，返回机票价格。")
        .build();

SubAgent flightAgent = SubAgent.from(config, chainActor)
        // 不调用 .llm()，由 llmFactory 在 master build() 时解析
        .tools(buildTool("get_flight_price", "查询机票价格", "city: String", tools::getFlightPrice))
        .build();

McpAgentExecutor master = McpAgentExecutor.builder(chainActor)
        .llm(ChatAliyun.builder().model("qwen-plus").temperature(0f).build())
        .llmFactory(modelName ->
            ChatAliyun.builder().model(modelName).temperature(0f).build()
        )
        .subAgent(flightAgent)
        .systemPrompt("你是旅行助手，机票查询任务请使用 flight_checker。")
        .build();

String result = master.invoke("帮我查一下上海到西安的机票").getText();
```

主 Agent `build()` 内部逻辑：

```java
} else if (llmFactory != null) {
    subAgent.injectLlmFactory(llmFactory); // 注入工厂，子代理按 model 名构建
}
```

`resolveLlm()` 在首次 `invoke()` 时执行 `factory.apply("qwen-turbo")`，LLM 实例延迟创建。

**llmFactory 的价值**：

- `llmFactory` 是集中的 LLM 注册表入口——新增模型只改工厂函数，所有 SubAgent 受益
- `SubAgentConfig` 里的 `model` 字段是字符串，可以来自配置文件或数据库，部署时无需改代码

```
主 Agent llmFactory: modelName → ChatAliyun(modelName)
                         ↓
flight_checker (model=qwen-turbo)  → ChatAliyun("qwen-turbo")
analyst        (model=qwen-max)    → ChatAliyun("qwen-max")
summarizer     (model=qwen-turbo)  → ChatAliyun("qwen-turbo")
```

---

## 五、allowedTools：从主 Agent 借用部分工具

SubAgent 不一定要自带所有工具。当工具已经在主 Agent 注册，SubAgent 只需声明 `allowedTools` 白名单，主 Agent 在 `build()` 时自动过滤并注入。

```java
Tool weatherTool = buildTool("get_weather",      "查询城市天气", "city: String", tools::getWeather);
Tool flightTool  = buildTool("get_flight_price", "查询机票价格", "city: String", tools::getFlightPrice);
Tool hotelTool   = buildTool("get_hotel_price",  "查询酒店均价", "city: String", tools::getHotelPrice);

// SubAgent 声明只借 get_weather 和 get_hotel_price，不需要 get_flight_price
SubAgentConfig config = SubAgentConfig.builder()
        .name("accommodation_advisor")
        .description("住宿建议专员。查询天气和酒店价格，给出住宿建议。当需要住宿信息时使用。")
        .allowedTools(List.of("get_weather", "get_hotel_price"))
        .systemPrompt("""
                你是住宿建议专员。
                收到城市名后，依次调用 get_weather 和 get_hotel_price，
                结合天气和酒店价格给出住宿选择建议。
                """)
        .build();

// SubAgent 自己没有 tools，全部来自 allowedTools 注入
SubAgent advisor = SubAgent.from(config, chainActor)
        .llm(llm)
        .onToolCall(tc  -> System.out.println("[accommodation_advisor] ToolCall: " + tc))
        .onObservation(obs -> System.out.println("[accommodation_advisor] Observation: " + obs))
        .build();

// 主 Agent 拥有全部 3 个工具，SubAgent 只会得到白名单里的两个
McpAgentExecutor master = McpAgentExecutor.builder(chainActor)
        .llm(ChatAliyun.builder().model("qwen-plus").temperature(0f).build())
        .tools(weatherTool, flightTool, hotelTool)
        .subAgent(advisor)
        .systemPrompt("你是旅行总助手，住宿建议任务请使用 accommodation_advisor。")
        .build();

String result = master.invoke("我想去桂林，帮我看看住哪里合适").getText();
```

注入流程：

```
主 Agent 工具：[get_weather, get_flight_price, get_hotel_price]
                       ↓ allowedTools 白名单过滤
SubAgent 内部工具：[get_weather, get_hotel_price]   ← get_flight_price 被拦截
```

**最小权限原则**：SubAgent 只能访问它明确声明需要的工具。即使主 Agent 有 50 个工具，SubAgent 也只会看到白名单里的那几个，不会误调用不相关的工具，也减少了 LLM 在选择工具时的噪声。

执行过程：

```
[accommodation_advisor] ToolCall: get_weather {"city":"桂林"}
[accommodation_advisor] Observation: 桂林：阵雨，23~30°C
[accommodation_advisor] ToolCall: get_hotel_price {"city":"桂林"}
[accommodation_advisor] Observation: 桂林：三星¥200/晚，四星¥380/晚

住宿建议：
桂林近期天气多雨，建议选择市区内的四星酒店（¥380/晚），出行更便利。
三星酒店（¥200/晚）性价比高，适合预算有限的旅行者。
3晚住宿预算：¥600（三星）~ ¥1140（四星）
```

---

## 六、SKILL 内嵌 SubAgent：两层嵌套

Skill 的 `agents/` 子目录可以放置轻量的 SubAgent 配置，这些 SubAgent 会被自动注册为 Skill 内部执行器的工具，形成"主 Agent → Skill → SubAgent"的两层嵌套。

**目录结构**：

```
skills/travel-planner/
  SKILL.md
  agents/
    budget-advisor.md     ← 内嵌 SubAgent，只能访问 Skill 自身工具集里的工具
```

**`budget-advisor.md`**：

```markdown
---
name: budget_advisor
description: 旅行预算顾问。根据城市名称查询酒店均价，计算3晚旅行的住宿预算。
             当需要估算旅行预算时调用。
allowed-tools:
  - get_hotel_price
---

你是旅行预算顾问，专注于住宿费用估算。
收到城市名称后：
1. 调用 get_hotel_price 查询该城市酒店均价
2. 按3晚计算住宿总费用（三星和四星两个档次）
3. 输出简洁的预算建议
```

内嵌 SubAgent 的 `allowed-tools` 引用的是 **Skill 自身工具集**中的工具（不是主 Agent 的工具），借用范围严格限定在 Skill 内部。

**代码**：

```java
SkillConfig config = ClasspathSkillConfigLoader.fromClasspath("skills/travel-planner");
System.out.println("内嵌 agents 数量: " + config.getAgents().size());
config.getAgents().forEach(a ->
    System.out.println("  - " + a.getName() + " allowedTools=" + a.getAllowedTools()));

Skill travelSkill = Skill.from(config, chainActor)
        .llm(llm)
        .tools(weatherTool, flightTool, hotelTool)
        .verbose(true)
        .build();

McpAgentExecutor master = McpAgentExecutor.builder(chainActor)
        .llm(ChatAliyun.builder().model("qwen-plus").temperature(0f).build())
        .skill(travelSkill)
        .systemPrompt("你是旅行总助手，旅行规划任务请使用 travel_planner 技能。")
        .build();

String result = master.invoke("我想去三亚旅游3晚，帮我估算一下住宿预算").getText();
```

执行时日志（`verbose(true)` 开启二级前缀）：

```
内嵌 agents 数量: 1
  - budget_advisor allowedTools=[get_hotel_price]

[skill:travel_planner] ToolCall: budget_advisor {"input":"三亚住宿预算，3晚"}
[skill:travel_planner>budget_advisor] ToolCall: get_hotel_price {"city":"三亚"}
[skill:travel_planner>budget_advisor] Observation: 三亚：三星¥480/晚，四星¥950/晚

========== SKILL 内嵌 SubAgent 结果 ==========
三亚3晚住宿预算：
- 三星酒店：¥480 × 3 = ¥1440
- 四星酒店：¥950 × 3 = ¥2850
建议：三亚旅游旺季酒店紧张，建议提前预订四星酒店...
```

二级日志前缀 `[skill:travel_planner>budget_advisor]` 清楚展示了调用链的层级关系，调试多层嵌套时一目了然。

---

## 七、四个进阶场景汇总

| 场景 | 关键 API | 典型用途 |
|------|---------|---------|
| `model=inherit` | AGENT.md `model: inherit` | 子任务与主任务用同一模型，减少配置冗余 |
| `llmFactory` | `.llmFactory(name -> ...)` | 多 SubAgent 使用不同模型，统一工厂管理 |
| `allowedTools` | `SubAgentConfig.allowedTools(...)` | SubAgent 借用父工具，最小权限隔离 |
| SKILL 内嵌 SubAgent | `agents/*.md` 目录 | Skill 内部进一步分解子任务 |

---

## 八、运行前置条件

1. **`ALIYUN_KEY`** 环境变量：示例使用 `qwen-plus` / `qwen-turbo`
2. `model=inherit` 和内嵌 SubAgent 模式需要对应的 `AGENT.md` 文件
3. `allowedTools` 和 `llmFactory` 模式可以用代码构造 `SubAgentConfig`，无文件依赖

---

## 九、总结

SubAgent 进阶能力让多 Agent 系统在模型选择和工具权限上更加灵活：

- **三级 LLM 优先链**覆盖了所有部署场景：显式注入用于测试，`inherit` 用于复用，`llmFactory` 用于差异化模型管理
- **`allowedTools` 白名单**保证最小权限，SubAgent 只能看到声明需要的工具，LLM 不会误调用无关工具
- **SKILL 内嵌 SubAgent** 让两层嵌套自然表达"Skill 把复杂子任务进一步委托给专属小代理"的意图，`verbose` 日志二级前缀让调用链透明可追踪

---

> 📎 相关资源
> - 完整代码：[Article23SubAgent.java](../../../src/test/java/org/salt/jlangchain/demo/article/Article23SubAgent.java)，方法 `testInheritModel` / `testLlmFactory` / `testAllowedToolsFromParent` / `testSkillWithEmbeddedAgent`
> - 内嵌子代理配置：[skills/travel-planner/agents/budget-advisor.md](../../../src/test/resources/skills/travel-planner/agents/budget-advisor.md)
> - 天气专员配置：[agents/weather-checker/AGENT.md](../../../src/test/resources/agents/weather-checker/AGENT.md)
> - j-langchain GitHub：https://github.com/flower-trees/j-langchain
> - 运行环境：`ALIYUN_KEY`（`qwen-plus` / `qwen-turbo`）
