# AgentExecutor: Launch a Java ReAct Agent Without Handwriting the Loop

> **Tags**: `Java` `ReAct` `Agent` `j-langchain` `LLM` `AgentExecutor` `Tool Use`  
> **Prerequisite**: [Implementing a ReAct Agent in Java: Tool Calling and Reasoning Loops](04-react-agent.md)  
> **Audience**: Java developers who understand the ReAct pattern and want to deploy it efficiently in projects

---

## I. The Problem Left From the Previous Article

The previous article built a complete ReAct Agent from scratch. The core code looked roughly like this:

```java
FlowInstance agentChain = chainActor.builder()
    .next(prompt)
    .loop(
        shouldContinue,
        llm,
        chainActor.builder()
            .next(cutAtObservation)   // Truncate content after Observation
            .next(parseAction)        // Parse Action / Action Input
            .next(
                Info.c(needsToolCall, executeTool),
                Info.c(input -> ContextBus.get().getResult(llm.getNodeId()))
            )
            .build()
    )
    .next(new StrOutputParser())
    .next(extractFinalAnswer)
    .build();

ChatGeneration result = chainActor.invoke(agentChain, Map.of("input", "What is the weather like in Shanghai right now?"));
```

Writing out the reasoning loop explicitly is very helpful for understanding the internals. But if every Agent in the project is written this way, the boilerplate code takes up a huge amount of space and every change has to be synchronized across multiple handlers.

j-langchain provides two tools to address this:

- **`AgentExecutor`**: encapsulates all the boilerplate above; start it with a one-line Builder API
- **`@AgentTool` annotation**: replaces `Tool.builder()` with annotations for a more concise, expressive tool definition

This article demonstrates how to use both tools and when to fall back to manual mode.

---

## II. Minimal Example: AgentExecutor Replaces the Handwritten Loop

The same weather-checking scenario, rewritten with `AgentExecutor`:

```java
@Test
public void reactAgentWithExecutor() {

    // 1. Define tools (same as in the previous article — this part is unchanged)
    Tool getWeather = Tool.builder()
        .name("get_weather")
        .params("location: String")
        .description("Get weather information for a city. Input: city name.")
        .func(location -> String.format("%s: sunny, 25°C", location))
        .build();

    Tool getTime = Tool.builder()
        .name("get_time")
        .params("city: String")
        .description("Get the current time for a city. Input: city name.")
        .func(city -> String.format("%s current time: 14:30", city))
        .build();

    // 2. Build the AgentExecutor — the ReAct loop is handled by the framework
    AgentExecutor agent = AgentExecutor.builder(chainActor)
        .llm(ChatAliyun.builder().model("qwen-plus").temperature(0f).build())
        .tools(getWeather, getTime)
        .maxIterations(10)
        .onThought(System.out::print)                                    // Callback after each reasoning step
        .onObservation(obs -> System.out.println("Observation: " + obs)) // Callback after each tool call
        .build();

    // 3. Execute
    ChatGeneration result = agent.invoke("What is the weather like in Shanghai right now?");
    System.out.println(result.getText());
}
```

Compared with the handwritten version, the truncation handler, Action parser, scratchpad concatenation logic, and Final Answer extractor are all gone — the framework handles them internally. **The execution result and internal mechanics are identical to the handwritten version**; you just don't need to care about the implementation details.

---

## III. Going Further: Define Tools with @AgentTool

`Tool.builder()` is clear, but it has many fields and can feel verbose. The `@AgentTool` annotation provides a more concise alternative.

### Single-parameter Tool

```java
public class CityTools {

    @AgentTool("Get weather information for a city. Input: city name.")
    public String getWeather(String location) {
        return String.format("%s: sunny, 25°C", location);
    }

    @AgentTool("Get the current time for a city. Input: city name.")
    public String getTime(String city) {
        return String.format("%s current time: 14:30", city);
    }
}
```

The annotation value is the tool description. The method name is automatically converted to snake_case as the tool name (`getWeather` → `get_weather`). The method's return value `.toString()` is used as the Observation.

### Multi-parameter Tool

When a tool requires multiple parameters, use `@Param` to describe each one. The LLM will generate JSON-formatted Action Input, which the framework automatically parses and injects by parameter name:

```java
@AgentTool("Book a flight. Requires departure city, destination city, and date.")
public String bookFlight(
        @Param("Departure city") String fromCity,
        @Param("Destination city") String toCity,
        @Param("Date in YYYY-MM-DD format") String date) {
    return String.format("Successfully booked %s → %s on %s", fromCity, toCity, date);
}
```

### Building an Agent with Annotated Tools

Pass the tool class instance directly to `tools()`, and the framework automatically scans all methods annotated with `@AgentTool`:

```java
@Test
public void reactAgentWithToolAnnotation() {

    AgentExecutor agent = AgentExecutor.builder(chainActor)
        .llm(ChatAliyun.builder().model("qwen-plus").temperature(0f).build())
        .tools(new CityTools())   // Pass the object; the framework scans @AgentTool methods automatically
        .maxIterations(10)
        .onThought(System.out::print)
        .onObservation(obs -> System.out.println("Observation: " + obs))
        .build();

    // Single-parameter tool call
    ChatGeneration result1 = agent.invoke("What is the weather like in Shanghai right now?");
    System.out.println(result1.getText());

    // Multi-parameter tool call
    ChatGeneration result2 = agent.invoke("Book me a flight from Shanghai to Beijing tomorrow, on 2024-03-15");
    System.out.println(result2.getText());
}
```

---

## IV. What the Reasoning Process Looks Like

### Single-parameter Tool Reasoning Trace

```
Thought: I need to query the weather in Shanghai. Calling get_weather.
Action: get_weather
Action Input: Shanghai

Observation: Shanghai: sunny, 25°C

Thought: I now have the weather information and can answer.
Final Answer: The weather in Shanghai is currently sunny, 25°C.
```

### Multi-parameter Tool Reasoning Trace

The LLM generates JSON-formatted Action Input; the framework parses and injects it by parameter name:

```
Thought: I need to book a flight. Calling book_flight.
Action: book_flight
Action Input: {"fromCity": "Shanghai", "toCity": "Beijing", "date": "2024-03-15"}

Observation: Successfully booked Shanghai → Beijing on 2024-03-15

Thought: Booking complete. I can inform the user.
Final Answer: Your flight from Shanghai to Beijing on 2024-03-15 has been booked.
```

---

## V. @AgentTool Annotation Quick Reference

| Feature | Description |
|---------|-------------|
| Tool name | Defaults to the method name converted to snake_case, e.g. `getWeather` → `get_weather`; override with `@AgentTool(name="...")` |
| Single parameter | Action Input is passed as a plain string |
| Multiple parameters | Action Input must be JSON; the framework auto-parses by parameter name, supporting both camelCase and snake_case |
| Parameter description | `@Param("description")` is injected into the Prompt to help the LLM understand each parameter |
| Return value | The method's return value `.toString()` is used as Observation |

## Builder Parameters Quick Reference

| Parameter | Required | Description |
|-----------|----------|-------------|
| `llm(...)` | Yes | Any `BaseChatModel` implementation (Ollama, Alibaba Cloud, OpenAI, etc.) |
| `tools(Tool...)` | Yes | Pass `Tool` objects directly as varargs |
| `tools(List<Tool>)` | Yes | Pass a list of `Tool` objects |
| `tools(Object)` | Yes | Pass an object with `@AgentTool` annotations; scanned automatically |
| `maxIterations(n)` | Optional | Maximum reasoning rounds; default is 10 |
| `promptTemplate(...)` | Optional | Custom ReAct Prompt |
| `onThought(...)` | Optional | Callback after each Thought/Action is generated |
| `onObservation(...)` | Optional | Callback after a tool call completes |

---

## VI. Comparison with Other Frameworks

| Feature | LangChain Python | LangChain4j | j-langchain |
|---------|-----------------|-------------|-------------|
| Underlying mechanism | ReAct Prompt | Function Calling | ReAct Prompt |
| Tool definition | `@tool` + Pydantic | `@Tool` annotation | `@AgentTool` + `@Param` |
| Multi-parameter handling | Pydantic → JSON Schema | Annotation reflection → JSON | `@Param` → JSON Schema → `method.invoke` |
| Simple entry point | `create_react_agent` | `AiServices` | `AgentExecutor.builder()` |
| Manual loop control | Difficult | Nearly impossible | `chainActor.builder().loop(...)` |
| Model dependency | Any model | Requires Function Calling support | Any model |
| Reasoning transparency | Medium | Low (highly encapsulated) | High (full pipeline is visible) |

One point worth noting: **LangChain4j's `AiServices` takes the Function Calling route, not true ReAct**. The model directly outputs structured JSON to decide which tool to call; the framework captures it, executes the call, and injects the result as a `ToolMessage` into the conversation history. The underlying mechanics are different, so direct code-conciseness comparisons are not on the same dimension.

---

## VII. When to Fall Back to Manual Mode

`AgentExecutor` covers most scenarios, but manual construction (as in the previous article) is recommended in these situations:

**Multi-LLM collaboration**: use a lightweight model for intent classification in the first round, then switch to a more capable model for reasoning. `AgentExecutor` only supports a single LLM.

**Custom termination conditions**: in addition to "are there still Actions to execute?", you need to check external state (e.g., task queues, database flags) to decide whether to continue.

**Audit hooks inside the loop**: each tool call needs an audit log, alert trigger, or risk-control intercept before and after execution.

**Non-standard Prompt format**: some privately deployed models require special adaptation of the ReAct Prompt format.

The two approaches can coexist: **use `AgentExecutor` to quickly validate business logic, then switch to manual mode when deep customization is needed** — you don't need to rewrite the tool definitions from scratch.

---

## VIII. Summary

`AgentExecutor` and `@AgentTool` solve the same problem: **stripping repetitive boilerplate code away from business logic**.

The manual implementation in the previous article lets you see every detail of ReAct; the encapsulated approach here lets you write less redundant code in real projects. Both are worth knowing — the former builds understanding, the latter accelerates delivery.

---

> Related resources
> - Full sample: [Article09AgentExecutor.java](../../../src/test/java/org/salt/jlangchain/demo/article/Article09AgentExecutor.java), methods `reactAgentWithExecutor()` and `reactAgentWithToolAnnotation()`
> - j-langchain GitHub: https://github.com/flower-trees/j-langchain
> - j-langchain Gitee mirror: https://gitee.com/flower-trees-z/j-langchain
