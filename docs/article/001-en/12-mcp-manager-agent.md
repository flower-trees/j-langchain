# McpAgentExecutor: Let Models Call HTTP Tools in a Few Lines

> **Tags**: `Java` `MCP` `Function Calling` `Agent` `j-langchain` `McpAgentExecutor` `tool calls`  
> **Prerequisite**: [Integrate the MCP Tool Protocol in Java](08-mcp.md) → [MCP + Function Calling: Let the Model Drive Multi-step Tool Chains](11-mcp-react-agent.md)  
> **Audience**: Java developers who have configured MCP tools and don't want to hand-write the Function Calling loop

---

## 1. The Boilerplate Left Behind by the Previous Article

The previous article implemented a complete "MCP + Function Calling" multi-step reasoning pipeline. The core parts looked roughly like this:

```java
// Loop condition: continue if there are ToolCalls
Function<Integer, Boolean> shouldContinue = round -> { ... };

// Tool execution handler: parse ToolCall → execute MCP → write back Observation
TranslateHandler<Object, AIMessage> executeMcpTool = new TranslateHandler<>(msg -> {
    // Record ToolCall into history
    // Parse tool name and arguments
    // Call mcpManager.runForInput(...)
    // Write result back as ToolMessage into Prompt
    ...
});

// Assemble the pipeline
FlowInstance chain = chainActor.builder()
    .next(prompt)
    .loop(shouldContinue, llm, chainActor.builder()
        .next(Info.c(needsToolExecution, executeMcpTool), Info.c(...))
        .build())
    .next(new StrOutputParser())
    .build();
```

This code structure is correct, and understanding it is valuable for mastering the underlying principles. But if your project has multiple Agents connecting MCP tools, writing this boilerplate every time is redundant.

`McpAgentExecutor` encapsulates this logic internally and exposes only what actually needs configuring: **which model to use, which tool group to load, and what system prompt to write**.

---

## 2. Minimal Example: Multi-step Tool Calls in Five Lines

```java
@Test
public void mcpManagerAgent() {

    McpAgentExecutor agent = McpAgentExecutor.builder(chainActor)
        .llm(ChatAliyun.builder().model("qwen3.6-plus").temperature(0f).build())
        .tools(mcpManager, "default")       // Load the default tool group from mcp.config.json
        .systemPrompt("你是一个智能助手，可以调用工具获取信息后回答用户问题。")
        .maxIterations(5)
        .onToolCall(tc -> System.out.println(">> Tool call: " + tc))
        .onObservation(obs -> System.out.println(">> Observation: " + obs))
        .build();

    ChatGeneration result = agent.invoke("帮我查一下我的公网 IP 是什么？");

    System.out.println("\n=== 最终答案 ===");
    System.out.println(result.getText());
}
```

`.tools(mcpManager, "default")` does all the preparation that had to be written manually in the previous article: reads the tool list for the `default` group from `mcp.config.json`, converts it to the JSON Schema needed for Function Calling, and registers it with the model.

---

## 3. Execution Result

```
>> Tool call: ToolCall(id=..., function=get_export_ip, arguments={})
>> Observation: 123.117.177.40

=== 最终答案 ===
你的公网 IP 为 123.117.177.40。
```

If you ask a question requiring multi-step reasoning — for example "check my public IP and tell me the current weather" — the model automatically completes multiple rounds of ToolCalls within a single `invoke`, completely transparent to the caller:

```
>> Tool call: get_export_ip → 123.117.177.40
>> Tool call: get_ip_location → {"city": "Dongcheng Qu", "latitude": 39.91, ...}
>> Tool call: get_weather_open_meteo → {"temperature": 18.3, "weathercode": 1}

=== 最终答案 ===
你位于北京市东城区，当前气温 18.3°C，天气晴朗。
```

The number of tools, call order, and parameter assembly are all decided by the model. The developer only needs to ensure the required tools are in the same group in `mcp.config.json`.

---

## 4. Comparison with the Hand-written Version

| Dimension | Hand-written Function Calling loop (Article 11) | McpAgentExecutor (this article) |
|---|---|---|
| Code volume | ~60 lines (loop, handler, pipeline assembly) | ~10 lines |
| Loop logic | Self-maintained `shouldContinue` and handler | Built into the framework |
| ToolCall parsing | Manual JSON parsing, write back ToolMessage | Handled automatically by the framework |
| Debug capability | Fully custom log placement | `onToolCall` / `onObservation` callbacks |
| Best for | Need to insert custom logic inside the loop | Standard tool calls, no customization needed |

The two approaches are not mutually exclusive. For scenarios `McpAgentExecutor` can't handle (e.g., permission checks before tool calls, or dynamically switching tool groups based on intermediate results), falling back to a hand-written loop is always an option.

---

## 5. Builder Parameter Reference

| Parameter | Required | Description |
|---|---|---|
| `llm(...)` | ✅ | A model that supports Function Calling, e.g. Aliyun `qwen3.6-plus`, OpenAI `gpt-4o` |
| `tools(mcpManager, group)` | ✅ | Load the specified tool group from `McpManager` |
| `systemPrompt(...)` | ✅ | Tell the model what it can do and the constraints of the task |
| `maxIterations(n)` | Optional | Maximum tool call rounds, default 5; increase for complex tasks |
| `onToolCall(...)` | Optional | Callback on each ToolCall — useful for instrumentation and auditing |
| `onObservation(...)` | Optional | Callback on tool execution results — useful for real-time progress display |

---

## 6. Practical Tips

**Be specific in the System Prompt.** A vague "you are an intelligent assistant" is usually not enough. Better to state the task goal and constraints clearly, for example:

```
你是一个网络诊断助手，可以调用工具检测公网 IP、查询地理位置和天气。
请按需调用工具，获取足够信息后直接给出结论，不要询问用户额外信息。
```

**Split tool groups by business domain.** `mcp.config.json` supports multiple groups; split by responsibility so each Agent loads only the group it needs:

```json
{
  "network": ["get_export_ip", "get_ip_location"],
  "weather": ["get_weather_open_meteo"],
  "crm":     ["query_customer", "update_order"]
}
```

This has two benefits: the model won't make incorrect calls because it saw unrelated tools; and each group's tool list is shorter, which improves Function Calling accuracy.

**Set `maxIterations` with headroom but not too large.** A rule of thumb: estimated maximum ToolCall count + 2. When set too large, if the model gets stuck in repeated calls it wastes more Token before the termination condition triggers.

---

## 7. Summary

`McpAgentExecutor` solves a straightforward problem: the ~60 lines of boilerplate from the previous article are now ~10 lines, with identical internal mechanics.

Tool maintenance and Agent logic are fully decoupled — updating tools only requires changing `mcp.config.json`; changing Agent behavior only requires adjusting `systemPrompt` and `maxIterations`. The two don't affect each other.

If you need your Agent to simultaneously operate file systems, databases, and other NPX MCP servers, the next article introduces the `McpClient` version of `McpAgentExecutor` — the integration approach is essentially the same as this one.

---

> 📎 Resources
> - Full example: [Article12McpManagerAgent.java](/src/test/java/org/salt/jlangchain/demo/article/Article12McpManagerAgent.java), method `mcpManagerAgent()`
> - Tool config: [mcp.config.json](/src/test/resources/mcp.config.json)
> - j-langchain GitHub: https://github.com/flower-trees/j-langchain
> - j-langchain Gitee mirror: https://gitee.com/flower-trees-z/j-langchain
> - Runtime requirements: Aliyun API Key required, example model `qwen3.6-plus`
