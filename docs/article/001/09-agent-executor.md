# AgentExecutor：告别手写 ReAct 循环，一行代码启动 Java Agent

> **标签**：`Java` `ReAct` `Agent` `j-langchain` `LLM` `AgentExecutor` `工具调用`  
> **前置阅读**：[Java 实现 ReAct Agent：工具调用与推理循环]()  
> **适合人群**：理解了 ReAct 原理、想在项目中高效落地的 Java 开发者

---

## 一、上篇留下的问题

上篇文章从零实现了一个完整的 ReAct Agent，核心代码大致是这样的：

```java
FlowInstance agentChain = chainActor.builder()
    .next(prompt)
    .loop(
        shouldContinue,
        llm,
        chainActor.builder()
            .next(cutAtObservation)   // 截断 Observation 之后的内容
            .next(parseAction)        // 解析 Action / Action Input
            .next(
                Info.c(needsToolCall, executeTool),
                Info.c(input -> ContextBus.get().getResult(llm.getNodeId()))
            )
            .build()
    )
    .next(new StrOutputParser())
    .next(extractFinalAnswer)
    .build();

ChatGeneration result = chainActor.invoke(agentChain, Map.of("input", "上海现在的天气怎么样？"));
```

把推理循环拆开来写，对理解原理非常有帮助。但如果项目中每个 Agent 都这样写，这些样板代码会占据大量篇幅，而且每次改动都要同步好几个处理器。

j-langchain 提供了两个工具来解决这个问题：

- **`AgentExecutor`**：把上面所有样板代码封装进去，Builder API 一行启动
- **`@AgentTool` 注解**：替代 `Tool.builder()`，用注解描述工具，更简洁直观

本文演示这两个工具的用法，以及在什么情况下该退回手动模式。

---

## 二、最简示例：AgentExecutor 替代手写循环

同样是查天气的场景，用 `AgentExecutor` 重写：

```java
@Test
public void reactAgentWithExecutor() {

    // 1. 定义工具（与上篇完全相同，这部分不变）
    Tool getWeather = Tool.builder()
        .name("get_weather")
        .params("location: String")
        .description("获取城市天气信息，输入城市名称")
        .func(location -> String.format("%s 天气晴，气温 25°C", location))
        .build();

    Tool getTime = Tool.builder()
        .name("get_time")
        .params("city: String")
        .description("获取城市当前时间，输入城市名称")
        .func(city -> String.format("%s 当前时间 14:30", city))
        .build();

    // 2. 构建 AgentExecutor —— ReAct 循环由框架处理，不用再手写
    AgentExecutor agent = AgentExecutor.builder(chainActor)
        .llm(ChatAliyun.builder().model("qwen-plus").temperature(0f).build())
        .tools(getWeather, getTime)
        .maxIterations(10)
        .onThought(System.out::print)                                    // 每轮推理结果回调
        .onObservation(obs -> System.out.println("Observation: " + obs)) // 工具执行结果回调
        .build();

    // 3. 执行
    ChatGeneration result = agent.invoke("上海现在的天气怎么样？");
    System.out.println(result.getText());
}
```

与手写版本相比，去掉了截断处理器、Action 解析器、scratchpad 拼接逻辑、Final Answer 提取器——这些统统由框架内部完成，**执行结果和内部机制与手写版完全一致**，只是不用再关心实现细节。

---

## 三、进一步简化：用 @AgentTool 注解定义工具

`Tool.builder()` 虽然直观，但字段较多，写起来还是有些冗余。`@AgentTool` 注解提供了更简洁的替代方式。

### 单参数工具

```java
public class CityTools {

    @AgentTool("获取城市天气信息，输入城市名称")
    public String getWeather(String location) {
        return String.format("%s 天气晴，气温 25°C", location);
    }

    @AgentTool("获取城市当前时间，输入城市名称")
    public String getTime(String city) {
        return String.format("%s 当前时间 14:30", city);
    }
}
```

注解的值就是工具描述，方法名自动转成 snake_case 作为工具名（`getWeather` → `get_weather`），方法返回值的 `toString()` 作为 Observation。

### 多参数工具

当工具需要多个参数时，用 `@Param` 描述每个参数，LLM 会生成 JSON 格式的 Action Input，框架自动按参数名解析注入：

```java
@AgentTool("订机票，需要出发城市、目的城市和日期")
public String bookFlight(
        @Param("出发城市") String fromCity,
        @Param("目的城市") String toCity,
        @Param("日期，格式 YYYY-MM-DD") String date) {
    return String.format("已成功预订 %s → %s，日期 %s 的机票", fromCity, toCity, date);
}
```

### 使用注解工具构建 Agent

把工具类实例直接传给 `tools()`，框架自动扫描所有带 `@AgentTool` 的方法：

```java
@Test
public void reactAgentWithToolAnnotation() {

    AgentExecutor agent = AgentExecutor.builder(chainActor)
        .llm(ChatAliyun.builder().model("qwen-plus").temperature(0f).build())
        .tools(new CityTools())   // 传对象，框架自动扫描 @AgentTool 方法
        .maxIterations(10)
        .onThought(System.out::print)
        .onObservation(obs -> System.out.println("Observation: " + obs))
        .build();

    // 单参数工具调用
    ChatGeneration result1 = agent.invoke("上海现在的天气怎么样？");
    System.out.println(result1.getText());

    // 多参数工具调用
    ChatGeneration result2 = agent.invoke("帮我订一张明天从上海飞北京的机票，日期是2024-03-15");
    System.out.println(result2.getText());
}
```

---

## 四、推理过程长什么样

### 单参数工具的推理轨迹

```
Thought: 需要查询上海的天气，调用 get_weather 工具。
Action: get_weather
Action Input: 上海

Observation: 上海 天气晴，气温 25°C

Thought: 已获得天气信息，可以直接回答。
Final Answer: 上海现在天气晴朗，气温 25°C。
```

### 多参数工具的推理轨迹

LLM 生成 JSON 格式的 Action Input，框架解析后按参数名注入方法：

```
Thought: 需要订机票，调用 book_flight 工具。
Action: book_flight
Action Input: {"fromCity": "上海", "toCity": "北京", "date": "2024-03-15"}

Observation: 已成功预订 上海 → 北京，日期 2024-03-15 的机票

Thought: 订票已完成，可以告知用户。
Final Answer: 已为您预订了 2024-03-15 从上海飞往北京的机票。
```

---

## 五、@AgentTool 注解规则速查

| 特性 | 说明 |
|---|---|
| 工具名 | 默认取方法名转 snake_case，如 `getWeather` → `get_weather`；可用 `@AgentTool(name="...")` 覆盖 |
| 单参数 | Action Input 直接传字符串 |
| 多参数 | Action Input 须为 JSON，框架按参数名自动解析，支持 camelCase 和 snake_case |
| 参数描述 | `@Param("描述")` 注入到 Prompt，帮助 LLM 理解每个参数的含义 |
| 返回值 | 方法返回值 `toString()` 作为 Observation |

## Builder 参数速查

| 参数 | 必填 | 说明 |
|---|---|---|
| `llm(...)` | ✅ | 任意 `BaseChatModel` 实现（Ollama、阿里云、OpenAI 等） |
| `tools(Tool...)` | ✅ | 直接传 `Tool` 对象，可变参数 |
| `tools(List<Tool>)` | ✅ | 传 `Tool` 列表 |
| `tools(Object)` | ✅ | 传带 `@AgentTool` 注解的对象，自动扫描 |
| `maxIterations(n)` | 可选 | 最大推理轮次，默认 10 |
| `promptTemplate(...)` | 可选 | 自定义 ReAct Prompt |
| `onThought(...)` | 可选 | 每轮 Thought/Action 生成后的回调 |
| `onObservation(...)` | 可选 | 工具执行完成后的回调 |

---

## 六、与其他框架对比

| 特性 | LangChain Python | LangChain4j | j-langchain |
|---|---|---|---|
| 底层机制 | ReAct Prompt | Function Calling | ReAct Prompt |
| 工具定义 | `@tool` + Pydantic | `@Tool` 注解 | `@AgentTool` + `@Param` |
| 多参数处理 | Pydantic → JSON Schema | 注解反射 → JSON | `@Param` → JSON Schema → `method.invoke` |
| 简洁入口 | `create_react_agent` | `AiServices` | `AgentExecutor.builder()` |
| 手动控制循环 | 较难 | 几乎不能 | `chainActor.builder().loop(...)` |
| 模型依赖 | 任意模型 | 需支持 Function Calling | 任意模型 |
| 推理过程透明度 | 中 | 低（高度封装） | 高（全流程可见） |

有一点值得特别说明：**LangChain4j 的 `AiServices` 走的是 Function Calling 路线，不是真正的 ReAct**。模型直接输出结构化 JSON 决定调用哪个工具，框架捕获后执行，再以 `ToolMessage` 注入对话历史。两者底层机制不同，代码简洁度的对比不在同一维度上。

---

## 七、什么时候该退回手动模式

`AgentExecutor` 覆盖了大多数场景，但以下情况建议退回上篇的手动构建方式：

**多 LLM 协作**：第一轮用轻量模型做意图分类，后续轮次换强模型推理，`AgentExecutor` 只支持单一 LLM。

**自定义终止条件**：除"是否还有 Action"之外，还需要检查外部状态（如任务队列、数据库标记）才能决定是否继续循环。

**循环内插入审计**：每次工具调用前后需要写审计日志、触发告警或做风控拦截。

**非标准 Prompt 格式**：对接某些私有化部署模型时，ReAct 的 Prompt 格式需要做特殊适配。

这两种方式可以共存：**先用 `AgentExecutor` 快速验证业务逻辑，确认需要深度定制时再切换到手动模式**，不需要从头重写工具定义部分。

---

## 八、总结

`AgentExecutor` 和 `@AgentTool` 解决的是同一个问题：**把重复的样板代码从业务逻辑中剥离出去**。

上篇的手动实现让你看清了 ReAct 的每一个细节，本篇的封装方式让你在实际项目中少写冗余代码。两者都有必要掌握，前者帮助理解，后者用于落地。

---

> 📎 相关资源
> - 完整示例：[Article09AgentExecutor.java](../../../src/test/java/org/salt/jlangchain/demo/article/Article09AgentExecutor.java)，对应方法 `reactAgentWithExecutor()` 和 `reactAgentWithToolAnnotation()`
> - j-langchain GitHub：https://github.com/flower-trees/j-langchain
> - j-langchain Gitee 镜像：https://gitee.com/flower-trees-z/j-langchain