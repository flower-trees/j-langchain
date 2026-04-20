# MCP + Function Calling: Let the Model Drive Multi-step Tool Chains

> **Tags**: `Java` `MCP` `Function Calling` `ReAct` `j-langchain` `ToolCall` `Agent`  
> **Prerequisite**: [ReAct Agent in Java: Tool Calls and Reasoning Loop](04-react-agent.md) → [Integrate the MCP Tool Protocol in Java](08-mcp.md)  
> **Audience**: Java developers who know MCP basics and want the model to natively drive tool calls

---

## 1. What the Previous Articles Did, and What This One Does

**Article 08** introduced the basic MCP integration: register HTTP tools with `McpManager`, call `run()` manually, then splice the result into the Prompt. This approach is good for validating tools, but "which tool to call and what parameters to pass" is decided by the developer — the model has no say.

**Articles 09/10** introduced `AgentExecutor`, which lets the model drive tool calls via text output in the ReAct format (`Action: xxx / Action Input: yyy`). This works with any model, but fundamentally it parses the model's text to infer intent.

**This article** takes a third approach: register the MCP tool manifest directly with a model that supports **Function Calling**. Instead of producing text-format Actions, the model outputs a structured **ToolCall** (function name + JSON arguments). We simply execute the ToolCall, write the result back to the Prompt, and let the model continue reasoning.

Three approaches summarized in one table:

| Approach | Who decides which tool to call | Tool parameter format | Model requirement |
|---|---|---|---|
| Manual MCP call | Developer | Anything | None |
| ReAct text-driven | Model (text output) | String / JSON text | Any |
| Function Calling | Model (structured output) | Standard JSON Schema | Must support Function Calling |

---

## 2. Scenario: Three Sequential Steps, Zero Human Intervention

The scenario in this article is automatic detection of the current public IP, city lookup, and weather query:

1. `get_export_ip`: get the local machine's public outbound IP
2. `get_ip_location`: look up city, coordinates, and ISP information from an IP
3. `get_weather_open_meteo`: query real-time weather from coordinates

The three tools must be **called in order, with each step's return value as the next step's input**. The user says one sentence; the Agent autonomously handles all reasoning and tool calls and delivers a "location + weather" summary.

All three tools are declared in the `default` group of `mcp.config.json` — no code changes needed for the framework to load them.

---

## 3. Core Code, Step by Step

### Step 1: Build the Prompt and Inject the Tool Manifest

```java
// Prompt template: system instruction specifies call order and output format
BaseRunnable<ChatPromptValue, ?> prompt = ChatPromptTemplate.fromMessages(
    List.of(
        BaseMessage.fromMessage(MessageType.SYSTEM.getCode(),
            """
            你是一名能够调用 MCP HTTP 工具的智能体，需要按以下顺序完成任务：
            1) 调用 get_export_ip 获取公网 IP；
            2) 将该 IP 传给 get_ip_location，获取城市、经纬度以及网络信息；
            3) 使用经纬度调用 get_weather_open_meteo，并设置 current_weather=true；
            4) 总结位置与天气（只输出结论，不暴露工具名称）。
            工具只在必要时调用，每个工具最多执行一次。
            """),
        BaseMessage.fromMessage(MessageType.HUMAN.getCode(), "用户问题：${input}")
    )
);

// manifestForInput() converts mcp.config.json into the JSON Schema format the model expects
List<AiChatInput.Tool> tools = mcpManager.manifestForInput().get("default");

// Register the tool manifest with the LLM; the model will decide when to call which tool
ChatAliyun llm = ChatAliyun.builder()
    .model("qwen3.6-plus")
    .temperature(0f)
    .tools(tools)
    .build();
```

The System Prompt explicitly specifies the call order. This has two benefits: it reduces the probability of the model skipping a key step, and it makes it easier to identify which step went wrong during debugging.

### Step 2: Loop Condition — Continue If There Are ToolCalls

```java
int maxIterations = 5;

Function<Integer, Boolean> shouldContinue = round -> {
    if (round >= maxIterations) {
        return false;  // prevent infinite loops
    }
    if (round == 0) {
        return true;   // always run the first round
    }
    // Check whether the last LLM output contained a ToolCall
    AIMessage lastAi = ContextBus.get().getResult(llm.getNodeId());
    return lastAi instanceof ToolMessage toolMessage
        && CollectionUtils.isNotEmpty(toolMessage.getToolCalls());
};
```

The exit condition is clear: when the model stops outputting ToolCalls, it has enough information and is ready to give a final answer.

### Step 3: The Core Handler — Execute ToolCalls and Write Back Observations

This is the most important section of the entire pipeline:

```java
TranslateHandler<Object, AIMessage> executeMcpTool = new TranslateHandler<>(msg -> {
    ChatPromptValue promptValue = ContextBus.get().getResult(prompt.getNodeId());

    // Not a ToolCall — model is generating its final answer, pass through
    if (!(msg instanceof ToolMessage toolMessage)) {
        return msg;
    }
    if (CollectionUtils.isEmpty(toolMessage.getToolCalls())) {
        return toolMessage;
    }

    // 1. Add the model's ToolCall request to the conversation history
    //    (the model needs to see this record in the next reasoning round)
    promptValue.getMessages().add(toolMessage);

    // 2. Parse the ToolCall: extract tool name and arguments
    AiChatOutput.ToolCall call = toolMessage.getToolCalls().get(0);
    Map<String, Object> args = parseArgs(call.getFunction().getArguments());
    String toolName = call.getFunction().getName();

    // 3. Execute the real HTTP request via McpManager
    System.out.println("[ToolCall] " + toolName + " params -> " + JsonUtil.toJson(args));
    Object result = mcpManager.runForInput("default", toolName, args);
    String observation = result != null ? result.toString() : "工具无返回内容";
    System.out.println("[Observation] " + observation);

    // 4. Append the result as a ToolMessage to the conversation history
    //    so the model can see "what I called and what I got" in the next round
    appendToolMessage(prompt, call, observation);

    return ContextBus.get().getResult(prompt.getNodeId());
});
```

`appendToolMessage` writes the Observation back into the Prompt as a standard `ToolMessage`, so the model can see the full context of "which tool I called and what result I got" in the next round.

### Step 4: Assemble the Full Pipeline

```java
FlowInstance chain = chainActor.builder()
    .next(prompt)
    .loop(
        shouldContinue,    // loop condition: continue if there are ToolCalls
        llm,               // call LLM each round
        chainActor.builder()
            .next(
                Info.c(needsToolExecution, executeMcpTool),            // ToolCall → execute tool
                Info.c(input -> ContextBus.get().getResult(llm.getNodeId())) // no ToolCall → pass through
            )
            .build()
    )
    .next(new StrOutputParser())
    .build();

ChatGeneration finalAnswer = chainActor.invoke(chain, Map.of(
    "input", "不要询问额外信息，自动检测我的公网 IP，推断所在城市并告知当前天气后统一回复。"
));

System.out.println(finalAnswer.getText());
```

The pipeline structure is very clear:

```
Prompt → loop( LLM → [ToolCall? execute MCP : pass through] ) → output final answer
```

---

## 4. Complete Reasoning Trace

After running, the console prints the complete reasoning trace:

```
> MCP Function-Calling ReAct 链开始执行...

[ToolCall] get_export_ip params -> {}
[Observation] 123.117.177.40

[ToolCall] get_ip_location params -> {"ip": "123.117.177.40"}
[Observation] {"country_name":"China","region_name":"Beijing Shi","city":"Dongcheng Qu",
               "latitude":39.9117,"longitude":116.4097,"org":"AS4134 Chinanet"}

[ToolCall] get_weather_open_meteo params -> {"latitude":39.9117,"longitude":116.4097,"current_weather":"true"}
[Observation] {"current_weather":{"temperature":18.3,"windspeed":6.1,"weathercode":1}}

> 链执行完成。

=== 最终回答 ===
检测到你的公网 IP 位于中国北京市东城区，当前温度约 18°C，天气晴朗，风速 6.1 km/h。
```

The model executed exactly three ToolCalls, missing none and adding none. After each Observation was written back to the Prompt, the model automatically extracted the required fields in the next round (IP → coordinates → weather) — no field mapping by the developer was needed.

---

## 5. The Essential Difference from ReAct Text-Driven

Many developers ask: `AgentExecutor` can also handle multi-step tool calls — what's the real difference?

**ReAct text-driven** (AgentExecutor): the model outputs plain text, for example:
```
Action: get_ip_location
Action Input: {"ip": "123.117.177.40"}
```
The framework extracts the tool name and parameters via string parsing — essentially "reading an article the model wrote."

**Function Calling** (this article): the model outputs structured JSON, for example:
```json
{"toolCalls": [{"function": {"name": "get_ip_location", "arguments": "{\"ip\":\"123.117.177.40\"}"}}]}
```
The framework parses JSON directly; tool name and parameters are strongly-typed fields with no format ambiguity.

When to use each:

| Scenario | Recommended approach | Reason |
|---|---|---|
| Model doesn't support Function Calling | ReAct text-driven | Only available option |
| Complex parameters (nested JSON, arrays) | Function Calling | Structured output is more reliable |
| Need precise control over reasoning text | ReAct text-driven | Thought content is fully readable |
| Integrating with standard MCP tool ecosystem | Function Calling | Tool Schema matches natively |
| High model stability requirements | Function Calling | Eliminates format parsing failures |

---

## 6. Handling Tool Call Failures

In production, HTTP tool calls may fail due to network timeouts, invalid parameters, etc. The framework's approach is to write the error as a `ToolMessage` back into the Prompt, letting the model know "this tool call failed" and allowing it to decide whether to retry, skip, or explain the situation to the user:

```java
try {
    Object result = mcpManager.runForInput("default", toolName, args);
    String observation = result != null ? result.toString() : "工具无返回内容";
    appendToolMessage(prompt, call, observation);
} catch (Exception e) {
    log.error("调用 MCP 工具 {} 失败: {}", toolName, e.getMessage(), e);
    // Write the error back to the Prompt; the model handles it in the next Thought
    appendToolMessage(prompt, call, "调用失败：" + e.getMessage());
}
```

This is far more graceful than throwing an exception — the model can say in its final answer "weather data unavailable, but here is your location information," instead of crashing the entire pipeline.

---

## 7. Summary

This article demonstrated a complete "MCP + Function Calling" multi-step reasoning pipeline. Compared to the manual approach in Article 08, the tool execution order and parameters here are entirely decided by the model. Compared to the ReAct text-driven approach in Articles 09/10, this uses the model's native ToolCall output, making parameter parsing more reliable.

The core idea in one sentence: **hand the MCP tool manifest to the model and let it decide what to call; the developer only executes and writes back results**.

If this loop code still feels verbose, the next article introduces `McpAgentExecutor`, which encapsulates the entire flow in one line.

---

> 📎 Resources
> - Full example: [Article11McpReactAgent.java](/src/test/java/org/salt/jlangchain/demo/article/Article11McpReactAgent.java), method `mcpFunctionCallingLoop()`
> - MCP tool config: [`src/test/resources/mcp.config.json`](/src/test/resources/mcp.config.json)
> - j-langchain GitHub: https://github.com/flower-trees/j-langchain
> - j-langchain Gitee mirror: https://gitee.com/flower-trees-z/j-langchain
> - Runtime requirements: Aliyun API Key required, example model `qwen3.6-plus`
