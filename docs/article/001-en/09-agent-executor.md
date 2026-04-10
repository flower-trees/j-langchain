# AgentExecutor: Start a Java ReAct Agent Without Handwriting the Loop

> **Tags**: Java, ReAct, Agent, j-langchain, LLM, AgentExecutor, Tool Use  
> **Prerequisite**: [ReAct Agent in Java](04-react-agent.md)  
> **Audience**: Developers who understand ReAct and want production-ready ergonomics

---

## 1. What We Built Previously

The manual ReAct loop looked like:

```java
FlowInstance agentChain = chainActor.builder()
    .next(prompt)
    .loop(
        shouldContinue,
        llm,
        chainActor.builder()
            .next(cutAtObservation)
            .next(parseAction)
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

Great for understanding, but verbose in production. j-langchain solves this with:

- **`AgentExecutor`**: wraps the entire loop behind a builder.
- **`@AgentTool`**: annotate methods instead of creating `Tool` objects manually.

---

## 2. Minimal Example with AgentExecutor

```java
@Test
public void reactAgentWithExecutor() {
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

    AgentExecutor agent = AgentExecutor.builder(chainActor)
        .llm(ChatAliyun.builder().model("qwen-plus").temperature(0f).build())
        .tools(getWeather, getTime)
        .maxIterations(10)
        .onThought(System.out::print)
        .onObservation(obs -> System.out.println("Observation: " + obs))
        .build();

    ChatGeneration result = agent.invoke("上海现在的天气怎么样？");
    System.out.println(result.getText());
}
```

All the scratchpad plumbing disappears; behavior matches the manual ReAct loop exactly.

---

## 3. Define Tools with `@AgentTool`

### Single-parameter tools

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

Method names are converted to snake_case tool names.

### Multi-parameter tools

```java
@AgentTool("订机票，需要出发城市、目的城市和日期")
public String bookFlight(
        @Param("出发城市") String fromCity,
        @Param("目的城市") String toCity,
        @Param("日期，格式 YYYY-MM-DD") String date) {
    return String.format("已成功预订 %s → %s，日期 %s 的机票", fromCity, toCity, date);
}
```

### Use annotated tools

```java
@Test
public void reactAgentWithToolAnnotation() {
    AgentExecutor agent = AgentExecutor.builder(chainActor)
        .llm(ChatAliyun.builder().model("qwen-plus").temperature(0f).build())
        .tools(new CityTools())
        .maxIterations(10)
        .onThought(System.out::print)
        .onObservation(obs -> System.out.println("Observation: " + obs))
        .build();

    ChatGeneration result1 = agent.invoke("上海现在的天气怎么样？");
    System.out.println(result1.getText());

    ChatGeneration result2 = agent.invoke("帮我订一张明天从上海飞北京的机票，日期是2024-03-15");
    System.out.println(result2.getText());
}
```

---

## 4. Execution Traces

**Single-parameter example**
```
Thought → call get_weather → Observation → Final Answer
```

**Multi-parameter example** (LLM emits JSON input)
```
Action Input: {"fromCity":"上海","toCity":"北京","date":"2024-03-15"}
Observation: 已成功预订 上海 → 北京，日期 2024-03-15 的机票
```

---

## 5. Annotation Cheat Sheet

| Feature | Notes |
|---------|-------|
| Tool name | Method name → snake_case; override with `name="..."` |
| Args | Single arg → string input; multiple args → JSON payload |
| `@Param` | Adds descriptions for the LLM and drives JSON schema |
| Return value | `toString()` is used as the Observation |

### Builder Options

| Method | Required | Description |
|--------|----------|-------------|
| `llm(...)` | Yes | Any `BaseChatModel` |
| `tools(...)` | Yes | Pass `Tool` objects or annotated instances |
| `maxIterations(n)` | No | Default 10 |
| `promptTemplate(...)` | No | Override ReAct prompt |
| `onThought(...)` | No | Callback after each Thought/Action |
| `onObservation(...)` | No | Callback after tool execution |

---

## 6. Framework Comparison

| Feature | LangChain (Py) | LangChain4j | j-langchain |
|---------|---------------|-------------|-------------|
| Mechanism | ReAct prompt | Function Calling | ReAct prompt |
| Tool definition | `@tool` | `@Tool` | `@AgentTool` + `@Param` |
| Multi-arg parsing | Pydantic | Reflection | Reflection + JSON schema |
| Entry point | `create_react_agent` | `AiServices` | `AgentExecutor.builder()` |
| Manual loop control | Hard | Almost none | Full control via `loop()` |
| Model requirement | Any | Must support FC | Any |
| Transparency | Medium | Low | High |

LangChain4j’s `AiServices` relies on Function Calling, not ReAct, so the ergonomics comparison is apples vs. oranges.

---

## 7. When to Go Back to Manual Loops

- **Multiple LLMs** inside one loop
- **Custom stop conditions** tied to external state
- **Audit/fraud hooks** before/after tool calls
- **Non-standard prompts** for private deployments

Start with `AgentExecutor` to iterate quickly; drop down to manual chains when you need advanced control.

---

## 8. Summary

`AgentExecutor` + `@AgentTool` remove repetitive boilerplate while keeping the underlying ReAct mechanics intact. Understand both the manual and the ergonomic approach so you can pick the right tool per scenario.

---

> Full sample: `Article09AgentExecutor.java`
