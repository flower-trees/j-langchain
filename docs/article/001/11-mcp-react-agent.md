# MCP Function-Calling ReAct：模型原生 ToolCall 实战

> **定位**：承接文章 08（MCP 基础）与文章 12（`McpAgentExecutor` 封装）之间的“过渡层”。  
> **配套代码**：`src/test/java/org/salt/jlangchain/demo/article/Article11McpReactAgent.java`

在 `Article08Mcp` 中，我们已经能用 `McpManager` 列出/调用 HTTP 工具。为了更贴近生产环境，本篇示例让支持 Function Calling 的模型（`qwen3.6-plus`）**直接消化 MCP 工具清单**，由模型负责判断何时调用工具、如何拼装参数，我们只负责执行 ToolCall 并回写 Observation。

---

## 场景：自动检测公网 IP + 城市 + 天气

1. `get_export_ip`：检测自身公网 IP；
2. `get_ip_location`：基于 ipapi 查询城市、经纬度、ISP；
3. `get_weather_open_meteo`：把经纬度传给 Open-Meteo，拿到实时天气。

用户什么都不用输入，Agent 需要连贯完成 3 次 ToolCall，并给出“位置 + 天气”总结。MCP 工具均在 `mcp.config.json` 的 `default` 组声明。

---

## Prompt 与工具注入

```java
BaseRunnable<ChatPromptValue, ?> prompt = ChatPromptTemplate.fromMessages(
    List.of(
        BaseMessage.fromMessage(MessageType.SYSTEM.getCode(),
            "系统指令：依次调用 get_export_ip → get_ip_location → get_weather_open_meteo，最终只输出结论"),
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

`manifestForInput()` 直接把 `mcp.config.json` 转成模型可用的 Tool schema。System Prompt 明确调用顺序与输出规范，避免模型漏掉关键步骤。

---

## 核心循环：监听 ToolCall → 调用 MCP → 写回提示词

```java
TranslateHandler<Object, AIMessage> executeMcpTool = new TranslateHandler<>(msg -> {
    ChatPromptValue promptValue = ContextBus.get().getResult(prompt.getNodeId());
    if (msg instanceof AIMessage aiMessage) {             // 保存 LLM 文本输出
        promptValue.getMessages().add(aiMessage);
    }
    if (!(msg instanceof ToolMessage toolMessage)) {      // 如果没有 ToolCall，就直接返回
        return (AIMessage) msg;
    }
    promptValue.getMessages().add(toolMessage);           // 记录模型的 ToolCall 请求

    AiChatOutput.ToolCall call = toolMessage.getToolCalls().get(0);
    Map<String,Object> args = parseArgs(call.getFunction().getArguments());

    Object result = mcpManager.runForInput("default", call.getFunction().getName(), args);
    String observation = result != null ? result.toString() : "工具无返回内容";
    appendToolMessage(prompt, call, observation);         // 以 ToolMessage 形式回写 Observation
    return ContextBus.get().getResult(prompt.getNodeId());
});
```

整个链路：
```
prompt → loop(LLM → ToolCall? → executeMcpTool) → StrOutputParser
```

`shouldContinue` 只在上一轮输出含 ToolCall 时继续，避免模型陷入死循环。Observation 会被追加到 Prompt 中，供下一轮模型参考。

---

## 执行效果

```
> MCP Function-Calling ReAct 链开始执行...
LLM 输出: {"toolCalls":[{"function":{"name":"get_export_ip","arguments":"{}"}}]}
[ToolCall] get_export_ip params -> {}
[Observation] 123.117.177.40

LLM 输出: {"toolCalls":[{"function":{"name":"get_ip_location","arguments":"{\"ip\":\"123.117.177.40\"}"}}]}
[Observation] {"country_name":"China","region_name":"Beijing Shi","city":"Dongcheng Qu","latitude":39.9117,...}

LLM 输出: {"toolCalls":[{"function":{"name":"get_weather_open_meteo","arguments":"{\"latitude\":39.9117,\"longitude\":116.4097,\"current_weather\":\"true\"}"}}]}
[Observation] {"current_weather":{"temperature":18.3,"windspeed":6.1,"weathercode":1,...}}

=== 最终回答 ===
检测到你的公网 IP 位于中国北京市东城区，实时温度约 18°C，天气晴朗。
```

模型仅输出了 3 次 ToolCall，我们只负责执行 MCP 请求并维护 Prompt 历史。相比文章 04 的手写 ReAct，这种方式更贴近 Function Calling 协议，也为文章 12 的 `McpAgentExecutor` 做好“心理铺垫”。

---

## 为什么值得这样做？

- **复用 MCP 基础设施**：无需额外编写 Tool schema，直接沿用 `mcp.config.json`；
- **可控的落地路径**：在进入 `McpAgentExecutor` 前，先理解模型与 MCP 交互的最细颗粒；
- **便于调试**：`executeMcpTool` 中可以打日志、做参数修正或补写 Observation，快速定位问题。

下一步：如果你希望**完全交给框架托管 Function Calling 循环**，继续阅读 [文章 12](12-mcp-manager-agent.md)，体验单行代码启动的 `McpAgentExecutor`。EOF
