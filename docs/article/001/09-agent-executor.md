# AgentExecutor：用一行代码启动 ReAct Agent

> **前置文章**：[Java 实现 ReAct Agent：工具调用与推理循环](04-react-agent.md)  
> **适合人群**：读完上篇、想直接用封装好的 Agent 的 Java 开发者  
> **核心概念**：AgentExecutor、@AgentTool 注解、多参数工具、多步骤推理  
> **配套代码**：`Article09AgentExecutor.java`

---

## 问题：ReAct 有没有更简洁的写法？

上篇文章完整展示了 ReAct 的推理循环——截断 Observation、解析 Action、执行工具、拼接 scratchpad……
这些步骤对于理解原理很有价值，但在项目中重复编写就显得繁琐。

j-langchain 提供了两种简洁方案：
- **`AgentExecutor`**：封装 ReAct 循环，Builder API 一键启动
- **`@AgentTool` 注解**：用注解定义工具，无需手写 `Tool.builder()`

---

## 对比：手动构建 vs AgentExecutor

**手动构建（上篇方式）**：
```java
// 需要自己写：截断处理器、解析处理器、工具执行器、scratchpad 拼接、Final Answer 提取……
FlowInstance agentChain = chainActor.builder()
    .next(prompt)
    .loop(shouldContinue, llm, chainActor.builder()
        .next(cutAtObservation)
        .next(parseAction)
        .next(Info.c(needsToolCall, executeTool), Info.c(...))
        .build())
    .next(new StrOutputParser())
    .next(extractFinalAnswer)
    .build();
ChatGeneration result = chainActor.invoke(agentChain, Map.of("input", "上海现在的天气怎么样？"));
```

**AgentExecutor + @AgentTool（本篇方式）**：
```java
ChatGeneration result = AgentExecutor.builder(chainActor)
    .llm(ChatAliyun.builder().model("qwen-plus").temperature(0f).build())
    .tools(new CityTools())   // 传注解对象，自动扫描 @AgentTool 方法
    .build()
    .invoke("上海现在的天气怎么样？");
```

两者执行结果完全一致，内部机制也完全相同。

---

## 用例：@AgentTool 注解（单参数 + 多参数）

### 工具定义

用 `@AgentTool` 替代 `Tool.builder()`，用 `@Param` 描述每个参数：

```java
public class CityTools {

    // 单参数工具：Action Input 直接传字符串
    @AgentTool("获取城市天气信息，输入城市名称")
    public String getWeather(String location) {
        return String.format("%s 天气晴，气温 25°C", location);
    }

    @AgentTool("获取城市当前时间，输入城市名称")
    public String getTime(String city) {
        return String.format("%s 当前时间 14:30", city);
    }

    // 多参数工具：Action Input 为 JSON，框架自动解析注入各参数
    @AgentTool("订机票，需要出发城市、目的城市和日期")
    public String bookFlight(
            @Param("出发城市") String fromCity,
            @Param("目的城市") String toCity,
            @Param("日期，格式 YYYY-MM-DD") String date) {
        return String.format("已成功预订 %s → %s，日期 %s 的机票", fromCity, toCity, date);
    }
}
```

### 构建 AgentExecutor

```java
AgentExecutor agent = AgentExecutor.builder(chainActor)
    .llm(ChatAliyun.builder().model("qwen-plus").temperature(0f).build())
    .tools(new CityTools())          // 传对象，框架自动扫描 @AgentTool 方法
    .maxIterations(10)
    .onThought(System.out::print)
    .onObservation(obs -> System.out.println("Observation: " + obs))
    .build();
```

### 执行

```java
// 单参数工具调用
ChatGeneration result1 = agent.invoke("上海现在的天气怎么样？");
System.out.println(result1.getText());

// 多参数工具调用：框架将 Action Input JSON 自动解析并注入 bookFlight 的三个参数
ChatGeneration result2 = agent.invoke("帮我订一张明天从上海飞北京的机票，日期是2024-03-15");
System.out.println(result2.getText());
```

### 单参数推理过程

```
Thought: 需要查询上海的天气。
Action: get_weather
Action Input: 上海

Observation: 上海 天气晴，气温 25°C

Thought: 已获得天气信息，可以回答了。
Final Answer: 上海现在天气晴朗，气温 25°C。
```

### 多参数推理过程

多参数工具的 `Action Input` 由 LLM 生成 JSON，框架解析后按参数名自动注入：

```
Thought: 需要订机票，调用 book_flight 工具。
Action: book_flight
Action Input: {"fromCity": "上海", "toCity": "北京", "date": "2024-03-15"}

Observation: 已成功预订 上海 → 北京，日期 2024-03-15 的机票

Final Answer: 已为您预订了2024-03-15从上海飞往北京的机票。
```

---

## 延伸阅读：多工具、多轮比价订票

需要**连续查询多个航司、比较结果、再调用订票工具**的完整业务流程，见 [文章 10：多步骤 ReAct——航司比价订票](10-flight-compare-agent.md)（工具类 `Article10FlightTools`，用例 `flightCompareAndBook()`）。

---

## @AgentTool 注解说明

| 特性 | 说明 |
|------|------|
| 工具名 | 默认取方法名转 snake_case（`getWeather` → `get_weather`），可用 `@AgentTool(name="...")` 覆盖 |
| 单参数 | Action Input 直接传字符串 |
| 多参数 | Action Input 须为 JSON，框架按参数名自动解析，支持 camelCase 和 snake_case |
| 参数描述 | `@Param("描述")` 注入到 Prompt，帮助 LLM 理解每个参数的含义 |
| 返回值 | 方法返回值 `toString()` 作为 Observation |

---

## Builder 参数说明

| 参数 | 必填 | 说明 |
|------|------|------|
| `llm(...)` | ✅ | 任意 `BaseChatModel` 实现（Ollama、阿里云、OpenAI 等） |
| `tools(Tool...)` | ✅ | 直接传 `Tool` 对象列表 |
| `tools(List<Tool>)` | ✅ | 传 `Tool` 列表 |
| `tools(Object)` | ✅ | 传带 `@AgentTool` 注解的对象，自动扫描 |
| `maxIterations(n)` | 可选 | 最大推理轮次，默认 10 |
| `promptTemplate(...)` | 可选 | 自定义 ReAct Prompt |
| `onThought(...)` | 可选 | 每轮 Thought/Action 生成后的回调 |
| `onObservation(...)` | 可选 | 工具执行完成后的回调 |

---

## 需要深度定制时怎么办？

`AgentExecutor` 覆盖了大多数常见场景。遇到以下情况时，退回上篇的手动构建方式：

- **多 LLM 协作**：第一轮用快模型分类，后续轮次用强模型推理
- **自定义终止条件**：除工具调用判断外，还需要检查外部状态
- **循环内插入审计**：每次工具调用前后需要写日志或触发告警
- **非标准 scratchpad 格式**：对接特定模型的私有 Prompt 格式

这两种方式可以共存：先用 `AgentExecutor` 快速验证，确认需要深度定制再切换到手动模式。

---

## 与其他框架对比

| 特性 | LangChain Python | LangChain4j | j-langchain |
|------|------------------|-------------|-------------|
| 底层机制 | ReAct Prompt | Function Calling | ReAct Prompt |
| 工具定义 | `@tool` + Pydantic | `@Tool` 注解 | `@AgentTool` + `@Param` |
| 多参数处理 | Pydantic → JSON Schema → `**kwargs` | 注解反射 → JSON | `@Param` → JSON Schema → `method.invoke` |
| 简洁模式 | `create_react_agent` | `AiServices` | `AgentExecutor.builder()` |
| 手动控制循环 | 较难 | 几乎不能 | `chainActor.builder().loop(...)` |
| 模型依赖 | 任意模型 | 需支持 Function Calling | 任意模型 |
| 推理过程透明度 | 中 | 低（高度封装） | 高（全流程可见） |

> **注意**：LangChain4j 的 `AiServices` 走的是 **Function Calling** 路线，不是真正的 ReAct。  
> 模型直接输出结构化 JSON 决定调用哪个工具，框架捕获后执行，再以 `ToolMessage` 注入对话历史——本质机制不同，代码简洁度不具可比性。

---

> 完整代码见：`src/test/java/org/salt/jlangchain/demo/article/Article09AgentExecutor.java`  
> 与本篇直接对应的方法：`reactAgentWithExecutor()`、`reactAgentWithToolAnnotation()`、`agentExecutorAsNode()`
