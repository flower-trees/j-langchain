# SubAgent 基础：拥有自主工具的子代理

> **标签**：`Java` `SubAgent` `子代理` `自主工具` `McpAgentExecutor` `j-langchain`  
> **前置阅读**：[Skill Agent：把子工作流封装成可复用的 Tool](22-skill-agent.md)  
> **适合人群**：已掌握 Skill 用法，希望构建拥有自有工具的独立子代理的 Java 开发者

---

## 一、Skill 的边界：为什么需要 SubAgent

上一篇介绍的 Skill 解决了子工作流封装问题，但有一个约束：**Skill 自己不拥有工具，它只能从父 Agent 借用**。这意味着如果子任务需要专属工具（如特定的 API 客户端、私有数据库连接），工具必须注册在主 Agent 上，Skill 再按名借用。

当子任务的独立性更高时，这种借用关系会变得别扭：

- 子任务有专属工具，不应暴露给主 Agent
- 子任务需要用不同的模型（性能/成本取舍）
- 多个子任务工具互不交叉，"借用"反而增加耦合

**SubAgent** 是为这类场景设计的：它拥有自己的工具，可以独立配置 LLM，对主 Agent 同样暴露为普通 `Tool`。

---

## 二、Skill vs SubAgent：核心区别

| 维度 | Skill | SubAgent |
|------|-------|----------|
| 工具来源 | 只能借用父 Agent 的工具 | 自有工具（可选借用父工具） |
| LLM | 继承主 Agent LLM | 可继承、可指定模型名、可显式注入 |
| 知识注入 | `references/` 文档拼入 system prompt | 关联 Skill 的知识拼入 system prompt |
| 配置文件 | `SKILL.md` | `AGENT.md` |
| 内部可组合性 | 可嵌入 SubAgent（`agents/`）| 可调用 Skill（作为 Tool）|
| 典型场景 | 标准化子流程、借用父工具 | 专业领域代理、自有专属工具 |

一句话区分：Skill 是"从父 Agent 借工具干活"，SubAgent 是"带着自己的工具干活"。

---

## 三、整体架构

```
┌───────────────────────────────────────────────────────────┐
│                      主 Agent (Master)                    │
│                                                           │
│   McpAgentExecutor                                        │
│   ├─ LLM (master LLM)                                     │
│   └─ Tool: travel_researcher  ← SubAgent 注册为普通 Tool  │
│                │                                          │
│                │ asTool()                                 │
│                ▼                                          │
│   ┌──────────────────────────────────────────────┐        │
│   │                  SubAgent                    │        │
│   │  config (AGENT.md)                           │        │
│   │  ownTools（自有工具，显式注册）                  │        │
│   │  inheritedTools（allowedTools 注入）          │        │
│   │         │                                    │        │
│   │         ▼  invoke()                          │        │
│   │  McpAgentExecutor（内部执行器）                │        │
│   │  ├─ LLM（三级优先链解析）                       │        │
│   │  ├─ systemPrompt（AGENT.md 正文）             │        │
│   │  ├─ ownTools                                 │        │
│   │  └─ inheritedTools（borrowed）                │        │
│   └──────────────────────────────────────────────┘        │
└───────────────────────────────────────────────────────────┘
```

---

## 四、AGENT.md：子代理的声明文件

`AGENT.md` 与 `SKILL.md` 格式相同，都是带 YAML 前言的 Markdown：

```
agents/travel-researcher/
  AGENT.md     ← 前言 + 系统提示正文
```

```markdown
---
name: travel_researcher
description: 旅行信息研究专家。负责综合查询目的地天气、机票、酒店信息，
             输出完整旅行建议。当用户需要获取旅行目的地综合信息时使用。
skills:
  - skills/travel-planner
max-iterations: 15
---

你是旅行信息研究专家，拥有专业的旅行规划知识。

你的职责是：
1. 理解用户的旅行需求，提取目的地城市
2. 调用可用工具查询天气、机票、酒店信息
3. 结合掌握的旅行规划知识，综合输出完整的旅行建议
```

| 前言字段 | 说明 |
|---------|------|
| `name` | SubAgent 标识符，也是 Tool 名称 |
| `description` | 主 LLM 看到的 Tool 描述，决定路由准确度 |
| `model` | `inherit`（继承主 LLM）/ 模型名（如 `qwen-plus`）/ 空（显式注入）|
| `tools` | 允许从父 Agent 借用的工具名白名单 |
| `skills` | 注入知识的 Skill 目录路径（知识注入，非工具调用）|
| `max-iterations` | 内部执行器最大迭代次数 |

> **注意**：`AGENT.md` 里的 `skills` 字段是**知识注入**，不是工具调用。Skill 的 `systemPrompt` 和 `references` 内容会拼接到 SubAgent 的 system prompt 里，让 SubAgent "知道"这些规范，但不会运行 Skill 的内部执行器。

---

## 五、用法一：SubAgent 独立运行

SubAgent 和 Skill 一样可以脱离主 Agent 独立调用，适合子任务本身就是完整业务的场景：

```java
TravelTools tools = new TravelTools();

SubAgentConfig config = ClasspathSubAgentConfigLoader.fromClasspath("agents/travel-researcher");
SubAgent researcher = SubAgent.from(config, chainActor)
        .llm(ChatAliyun.builder().model("qwen-plus").temperature(0f).build())
        .tools(
            buildTool("get_weather",      "查询城市天气", "city: String", tools::getWeather),
            buildTool("get_flight_price", "查询机票价格", "city: String", tools::getFlightPrice),
            buildTool("get_hotel_price",  "查询酒店均价", "city: String", tools::getHotelPrice)
        )
        .onToolCall(tc  -> System.out.println("[ToolCall]    " + tc))
        .onObservation(obs -> System.out.println("[Observation] " + obs))
        .build();

String result = researcher.invoke("我想去三亚旅游，出发地上海");
System.out.println(result);
```

执行过程：

```
[ToolCall]    get_weather {"city":"三亚"}
[Observation] 三亚：晴，28~33°C，紫外线强
[ToolCall]    get_flight_price {"city":"三亚"}
[Observation] 上海→三亚：¥1600，含15kg行李
[ToolCall]    get_hotel_price {"city":"三亚"}
[Observation] 三亚：三星¥480/晚，四星¥950/晚

========== SubAgent 独立运行结果 ==========
**三亚旅游建议**

天气：晴朗，28~33°C，紫外线强烈，建议携带防晒用品

机票：上海→三亚 ¥1600（含15kg行李）

住宿（3晚预算）：
- 三星酒店：¥480/晚 × 3晚 = ¥1440
- 四星酒店：¥950/晚 × 3晚 = ¥2850

综合预算：¥3040（三星）~ ¥4450（四星）
```

---

## 六、用法二：AGENT.md 加载 + 注册到 Master Agent

更常见的用法是把 SubAgent 挂到主 Agent 上，由主 Agent 负责任务路由：

```java
var llm = ChatAliyun.builder().model("qwen-plus").temperature(0f).build();

SubAgentConfig config = ClasspathSubAgentConfigLoader.fromClasspath("agents/travel-researcher");
SubAgent researcher = SubAgent.from(config, chainActor)
        .llm(llm)
        .tools(
            buildTool("get_weather",      "查询城市天气", "city: String", tools::getWeather),
            buildTool("get_flight_price", "查询机票价格", "city: String", tools::getFlightPrice),
            buildTool("get_hotel_price",  "查询酒店均价", "city: String", tools::getHotelPrice)
        )
        .verbose(true)
        .build();

McpAgentExecutor master = McpAgentExecutor.builder(chainActor)
        .llm(ChatAliyun.builder().model("qwen-plus").temperature(0f).build())
        .subAgent(researcher)
        .systemPrompt("你是旅行总助手，遇到旅行信息查询任务请使用 travel_researcher。")
        .onToolCall(tc  -> System.out.println("[master] ToolCall: " + tc))
        .onObservation(obs -> System.out.println("[master] Observation: " + obs))
        .build();

String result = master.invoke("我想从上海出发去成都和西安，帮我查一下旅行信息").getText();
```

主 Agent 的视角：

```
[master] ToolCall: travel_researcher {"input":"我想从上海出发去成都和西安，帮我查一下旅行信息"}
[subagent:travel_researcher] ToolCall: get_weather {"city":"成都"}
[subagent:travel_researcher] Observation: 成都：多云，18~26°C，下午有小雨
...
[master] Observation: （SubAgent 整合后的旅行报告）
```

主 Agent 只看到一次 `travel_researcher` 工具调用，SubAgent 内部的多次工具调用对主 Agent 完全透明。

---

## 七、用法三：代码直接构造 SubAgentConfig

不需要 `AGENT.md` 文件，直接在代码里组装配置——适合测试场景或动态生成 SubAgent：

```java
SubAgentConfig config = SubAgentConfig.builder()
        .name("weather_flight_agent")
        .description("查询目的地天气和机票信息的专属 Agent")
        .systemPrompt("""
                你是出行信息专家。
                收到城市名后，依次调用 get_weather 和 get_flight_price，
                最后整合结果输出简洁的出行参考。
                """)
        .build();

SubAgent agent = SubAgent.from(config, chainActor)
        .llm(ChatAliyun.builder().model("qwen-plus").temperature(0f).build())
        .tools(
            buildTool("get_weather",      "查询城市天气", "city: String", tools::getWeather),
            buildTool("get_flight_price", "查询机票价格", "city: String", tools::getFlightPrice)
        )
        .onToolCall(tc  -> System.out.println("[ToolCall]    " + tc))
        .onObservation(obs -> System.out.println("[Observation] " + obs))
        .build();

String result = agent.invoke("我打算去桂林，帮我查一下");
System.out.println(result);
```

`SubAgentConfig` 与 `SkillConfig` 一样与存储介质无关：classpath 加载、数据库读取、代码构造三种来源对 `SubAgent.from()` 完全透明。

---

## 八、三种用法的选择依据

| 场景 | 推荐用法 | 原因 |
|------|---------|------|
| 生产环境，多个子代理协作 | AGENT.md + Master | 配置文件可独立维护，主 Agent 统一路由 |
| 子任务完整独立 | SubAgent 独立运行 | 最简路径，无需主 Agent |
| 测试 / 快速原型 | 代码构造 SubAgentConfig | 无文件依赖，配置即代码 |

---

## 九、运行前置条件

1. **`ALIYUN_KEY`** 环境变量：示例使用 `qwen-plus`
2. classpath 模式需在 `src/test/resources/` 下放置 `agents/travel-researcher/AGENT.md`
3. 代码构造模式无文件依赖

---

## 十、总结

SubAgent 在 Skill 的基础上进一步提升了子代理的自主性：

- **自有工具**：工具注册在 SubAgent 自身，不依赖父 Agent 持有
- **统一接口**：对主 Agent 暴露为普通 `Tool`，与 Skill 接口一致，可混用
- **三种配置来源等价**：classpath AGENT.md、数据库、代码构造效果完全相同
- **可观测性内置**：`verbose(true)` 输出 `[subagent:<name>]` 前缀日志，精细回调适合生产监控

下一篇将介绍 SubAgent 的进阶能力：三种 LLM 配置策略（显式注入 / 继承主 Agent / 按名工厂构建）、`allowedTools` 最小权限借用，以及 Skill 内嵌 SubAgent 的组合模式。

---

> 📎 相关资源
> - 完整代码：[Article23SubAgent.java](../../../src/test/java/org/salt/jlangchain/demo/article/Article23SubAgent.java)，方法 `testSubAgentStandalone` / `testSubAgentWithMasterAgent` / `testSubAgentWithCodeConfig`
> - 子代理配置：[agents/travel-researcher/AGENT.md](../../../src/test/resources/agents/travel-researcher/AGENT.md)
> - j-langchain GitHub：https://github.com/flower-trees/j-langchain
> - 运行环境：`ALIYUN_KEY`（`qwen-plus`）
