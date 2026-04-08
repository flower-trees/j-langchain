# McpAgentExecutor + McpManager：HTTP 工具一键托管

> **场景**：你已经在文章 08 配置了超过一个 HTTP MCP 工具（天气、IP、内部 API...），不想再手写 ReAct 循环，让模型自己挑选工具、多轮调用。  
> **配套代码**：`src/test/java/org/salt/jlangchain/demo/article/Article12McpManagerAgent.java`

`McpAgentExecutor` 内置了 Function Calling 循环，支持自动读取 `mcp.config.json` 某个工具组，生成结构化 Tool schema 并交给模型。你要做的只有三件事：指定模型、告诉它工具组、写一句系统提示。

---

## 为什么要用 McpAgentExecutor？

- **免维护循环**：相比文章 11 的手写 ReAct，不用关心 Thought/Action/Observation 的 glue code。
- **工具热插拔**：修改 `mcp.config.json` 后，`McpManager.toTools()` 自动生效，Agent 层无需改动。
- **调试友好**：`onToolCall`、`onObservation` 回调能实时打印模型挑选的工具和返回值。

---

## 快速上手

```java
McpAgentExecutor agent = McpAgentExecutor.builder(chainActor)
    .llm(ChatAliyun.builder().model("qwen3.6-plus").temperature(0f).build())
    .tools(mcpManager, "default")          // 将 mcp.config.json 的 default 组注册为 Tool 列表
    .systemPrompt("你是一个智能助手，可以调用工具获取信息后回答用户问题。")
    .maxIterations(5)
    .onToolCall(tc -> System.out.println(">> Tool: " + tc))
    .onObservation(obs -> System.out.println(">> Observation: " + obs))
    .build();

ChatGeneration result = agent.invoke("帮我查一下我的公网 IP 是多少？");
```

`tools(mcpManager, "default")` 会自动把 `mcp.config.json` 顶层 `default` 数组里的每个 HTTP 工具转换成 Function Calling schema，再交给模型。系统提示只需告诉模型“可以调用工具获取答案”。

---

## 输出示例

```
>> Tool: ToolCall(id=..., function=get_export_ip)
>> Observation: 123.117.177.40

=== 最终答案 ===
你的公网 IP 为 123.117.177.40。
```

多轮提问时，模型可能会选择 `get_ip_location`、`query_weather` 等其它工具，你只需保证它们在 `mcp.config.json` 中处于同一分组即可。

---

## 调优建议

| 调优项 | 说明 |
|--------|------|
| `systemPrompt` | 描述可用工具和任务约束，避免模型盲猜；支持自定义语言、上下文提醒 |
| `maxIterations` | 默认 5 轮即可；遇到复杂流程时可适当增大，但也要提醒模型“完成后停止” |
| `onToolCall` | 可根据 Tool 名做自定义埋点、权限校验等 |
| `mcp.config.json` | 建议按业务域分组（如 `default`、`crm`、`ops`），方便不同 Agent 复用 |

---

## 与其它文章的关系

- 想看“模型原生 ToolCall + 手写循环”的底层细节？回到 [文章 11](11-mcp-react-agent.md)。
- 想让 Agent 操作 NPX MCP 服务器（filesystem/memory/postgres）？继续阅读 [文章 13](13-mcp-client-agent.md)。
- 想同时挂载 HTTP + NPX 工具？观看 [文章 14](14-mcp-mixed-agent.md)。
