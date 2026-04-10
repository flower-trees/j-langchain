# MCP + Function Calling: Let the Model Drive Multi-step Tool Chains

> **Tags**: Java, MCP, Function Calling, ReAct, j-langchain, ToolCall  
> **Prerequisites**: [ReAct Agent](04-react-agent.md) → [MCP integration](08-mcp.md)

---

## 1. Context

- **Article 08**: Register HTTP tools with `McpManager` and call them manually. Useful for validation, but humans decide which tool to run.
- **Articles 09/10**: Use `AgentExecutor` (ReAct text). The model emits `Action:`/`Action Input:` as plain text; the framework parses strings.
- **This article**: Register MCP tools directly with an LLM that supports **Function Calling**. The model outputs structured **ToolCall** objects (function name + JSON args). We execute them and feed the results back.

| Approach | Who chooses the tool? | Argument format | Model requirement |
|----------|-----------------------|-----------------|-------------------|
| Manual MCP call | Developer | Anything | None |
| ReAct text | Model (text) | Strings/JSON snippets | Any LLM |
| Function Calling | Model (structured) | JSON Schema | LLM must support FC |

---

## 2. Scenario

Detect the current public IP, map it to a city, then fetch the weather:

1. `get_export_ip`
2. `get_ip_location`
3. `get_weather_open_meteo`

All tools are declared in the `default` group of `mcp.config.json`.

---

## 3. Code Walkthrough

### Prompt + tools

```java
BaseRunnable<ChatPromptValue, ?> prompt = ChatPromptTemplate.fromMessages(
    List.of(
        BaseMessage.fromMessage(MessageType.SYSTEM.getCode(), """
            你是一名能够调用 MCP HTTP 工具的智能体，需要按以下顺序完成任务：
            1) 调用 get_export_ip 获取公网 IP；
            2) 将该 IP 传给 get_ip_location；
            3) 使用经纬度调用 get_weather_open_meteo，并设置 current_weather=true；
            4) 总结位置与天气。
            工具只在必要时调用，每个工具最多执行一次。
            """),
        BaseMessage.fromMessage(MessageType.HUMAN.getCode(), "用户问题：${input}")
    )
);

List<AiChatInput.Tool> tools = mcpManager.manifestForInput().get("default");

ChatAliyun llm = ChatAliyun.builder()
    .model("qwen3.6-plus")
    .temperature(0f)
    .tools(tools)
    .build();
```

### Loop condition

```java
Function<Integer, Boolean> shouldContinue = round -> {
    if (round >= maxIterations) return false;
    if (round == 0) return true;
    AIMessage lastAi = ContextBus.get().getResult(llm.getNodeId());
    return lastAi instanceof ToolMessage toolMessage
        && CollectionUtils.isNotEmpty(toolMessage.getToolCalls());
};
```

### Execute ToolCalls

```java
TranslateHandler<Object, AIMessage> executeMcpTool = new TranslateHandler<>(msg -> {
    ChatPromptValue promptValue = ContextBus.get().getResult(prompt.getNodeId());
    if (!(msg instanceof ToolMessage toolMessage) || CollectionUtils.isEmpty(toolMessage.getToolCalls())) {
        return msg;
    }

    promptValue.getMessages().add(toolMessage);

    AiChatOutput.ToolCall call = toolMessage.getToolCalls().get(0);
    Map<String, Object> args = parseArgs(call.getFunction().getArguments());
    String toolName = call.getFunction().getName();

    System.out.println("[ToolCall] " + toolName + " -> " + JsonUtil.toJson(args));
    Object result = mcpManager.runForInput("default", toolName, args);
    String observation = result != null ? result.toString() : "工具无返回内容";
    System.out.println("[Observation] " + observation);

    appendToolMessage(prompt, call, observation);
    return ContextBus.get().getResult(prompt.getNodeId());
});
```

### Chain assembly

```java
FlowInstance chain = chainActor.builder()
    .next(prompt)
    .loop(
        shouldContinue,
        llm,
        chainActor.builder()
            .next(
                Info.c(needsToolExecution, executeMcpTool),
                Info.c(input -> ContextBus.get().getResult(llm.getNodeId()))
            )
            .build()
    )
    .next(new StrOutputParser())
    .build();

ChatGeneration answer = chainActor.invoke(chain, Map.of(
    "input", "不要询问额外信息，自动检测我的公网 IP..."
));
System.out.println(answer.getText());
```

---

## 4. Execution Trace

```
[ToolCall] get_export_ip
[Observation] 123.117.177.40

[ToolCall] get_ip_location
[Observation] {..."city":"Dongcheng Qu"...}

[ToolCall] get_weather_open_meteo
[Observation] {"current_weather":{"temperature":18.3,...}}

Final answer: City + weather summary
```

---

## 5. ReAct Text vs. Function Calling

- **ReAct text**: the model writes `Action:` / `Action Input:` strings. The framework parses text.
- **Function Calling**: the model returns structured JSON; there’s no ambiguity.

| Scenario | Prefer | Reason |
|----------|--------|--------|
| Model lacks FC support | ReAct text | Only option |
| Complex argument schema | Function Calling | Structured types |
| Need fully readable Thought text | ReAct text | Everything stays textual |
| Working with MCP schemas | Function Calling | One-to-one mapping |
| High robustness | Function Calling | No string parsing |

---

## 6. Error Handling

Wrap MCP calls and append failures as ToolMessages so the model can react instead of crashing the chain.

---

## 7. Summary

Hand control over to the LLM: provide MCP tools via `manifestForInput()`, let the model emit ToolCalls, execute them, and feed back observations. This approach keeps tools declarative and reuses the MCP ecosystem.

Next up: `McpAgentExecutor`, which wraps the entire loop.

---

> Full sample: `Article11McpReactAgent.java` (`mcpFunctionCallingLoop`) – requires Aliyun `qwen3.6-plus`.
