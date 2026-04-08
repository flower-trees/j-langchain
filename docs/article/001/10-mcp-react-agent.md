# MCP Function-Calling ReAct：模型原生 ToolCall 实战

> **前置**：[04 - ReAct Agent](04-react-agent.md)、[08 - MCP 协议](08-mcp.md)  
> **后续**：[11 - McpAgentExecutor](11-mcp-manager-agent.md)  
> **配套代码**：`src/test/java/org/salt/jlangchain/demo/article/Article10McpReactAgent.java`

本文展示如何让支持 Function Calling 的模型（示例：阿里云 `qwen3.6-plus`）通过 MCP HTTP 工具完成「自动检测公网 IP → 获取 IP 归属地（含经纬度）→ 查询实时天气」的链路。我们手写一个 ReAct 风格循环：模型输出 ToolCall，我们解析 JSON、调用 MCP 工具，把结果当作 `ToolMessage` 追加回 Prompt，再进入下一轮，直到模型产出最终回答。

---

## 工具清单 & 场景

`mcp.config.json` 的 `default` 组准备了 3 个 HTTP MCP 工具：

| 工具 | 作用 |
|------|------|
| `get_export_ip` | 返回当前公网 IP（`http://ipinfo.io/ip`） |
| `get_ip_location` | 通过 ipapi 查询 IP 的城市、经纬度、时区等 |
| `get_weather_open_meteo` | 调用 Open-Meteo，根据经纬度获取当前天气 |

用户没有提供任何地理信息，Agent 需依次完成上述步骤并总结结论。

---

## Prompt 与 Function-Calling 配置

```java
BaseRunnable<ChatPromptValue, ?> prompt = ChatPromptTemplate.fromMessages(
    List.of(
        BaseMessage.fromMessage(MessageType.SYSTEM.getCode(), "系统指令：依次调用 get_export_ip → get_ip_location → get_weather_open_meteo"),
        BaseMessage.fromMessage(MessageType.HUMAN.getCode(), "用户问题：${input}")
    )
);

List<AiChatInput.Tool> tools = mcpManager.manifestForInput().get("default");
ChatAliyun llm = ChatAliyun.builder()
    .model("qwen3.6-plus")
    .temperature(0f)
    .tools(tools) // 直接把 MCP 工具清单交给模型
    .build();
```

`manifestForInput()` 会生成 Function Calling 所需的 `AiChatInput.Tool` 对象（包含 JSON Schema），模型因此知道有哪些函数可用、需要哪些参数。

---

## ReAct 循环：ToolCall → MCP → ToolMessage

```java
TranslateHandler<Object, AIMessage> executeMcpTool = new TranslateHandler<>(msg -> {
    ToolMessage toolMessage = (ToolMessage) msg;
    AiChatOutput.ToolCall call = toolMessage.getToolCalls().get(0);
    Map<String,Object> args = parseArgs(call.getFunction().getArguments());

    Object result = mcpManager.runForInput("default", call.getFunction().getName(), args);
    String observation = result != null ? result.toString() : "工具无返回内容";
    appendToolMessage(prompt, call, observation);
    return ContextBus.get().getResult(prompt.getNodeId());
});
```

- 当模型输出 `ToolCall` 时，`executeMcpTool` 解析 JSON 参数、调用 MCP 工具并把结果转成 `ToolMessage` 追加到 Prompt。  
- 下一轮模型就能看到之前的 `ToolCall` + `Observation`，决定是否继续或给出答案。  
- 循环退出条件是：已达到最大轮次或当前输出不是 ToolCall。

链路：`prompt → loop(LLM → ToolCall? → executeMcpTool) → StrOutputParser`。

---

## 运行示例

```
> MCP Function-Calling ReAct 链开始执行...
LLM 输出: {"toolCalls":[{"function":{"name":"get_export_ip","arguments":"{}"}}]}
[ToolCall] get_export_ip params -> {}
[Observation] 123.117.177.40

LLM 输出: {"toolCalls":[{"function":{"name":"get_ip_location","arguments":"{\"ip\":\"123.117.177.40\"}"}}]}
[ToolCall] get_ip_location params -> {"ip":"123.117.177.40"}
[Observation] {"country_name":"China","region_name":"Beijing Shi","city":"Dongcheng Qu","latitude":39.9117,"longitude":116.4097,...}

LLM 输出: {"toolCalls":[{"function":{"name":"get_weather_open_meteo","arguments":"{\"latitude\":39.9117,\"longitude\":116.4097,\"current_weather\":\"true\"}"}}]}
[ToolCall] get_weather_open_meteo params -> {"latitude":39.9117,"longitude":116.4097,"current_weather":"true"}
[Observation] {"current_weather":{"temperature":18.3,"windspeed":6.1,"weathercode":1,...}}

=== 最终回答 ===
已经确认你的公网 IP 位于中国北京市东城区，实时天气晴朗，温度约 18°C。
```

模型自动完成了三次 ToolCall，我们只负责执行工具与记录 Observation。与文章 04 的纯文本 ReAct 相比，Function Calling 版本更贴近真实生产环境；若想彻底省去手写循环，可继续阅读文章 11，使用 `McpAgentExecutor` 一键托管。 
