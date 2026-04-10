# McpAgentExecutor: Let Models Call HTTP Tools in a Few Lines

> **Tags**: Java, MCP, Function Calling, Agent, j-langchain  
> **Prerequisites**: [MCP integration](08-mcp.md) → [MCP + Function Calling loop](11-mcp-react-agent.md)

---

## 1. Why

Article 11 built the Function Calling loop manually (loop condition, ToolCall parser, observation writer). Great for learning, but repetitive across agents.

`McpAgentExecutor` wraps that boilerplate. You only specify **which model**, **which MCP tool group**, and **the system prompt**.

---

## 2. Minimal Example

```java
@Test
public void mcpManagerAgent() {
    McpAgentExecutor agent = McpAgentExecutor.builder(chainActor)
        .llm(ChatAliyun.builder().model("qwen3.6-plus").temperature(0f).build())
        .tools(mcpManager, "default")
        .systemPrompt("你是一个智能助手，可以调用工具获取信息后回答用户问题。")
        .maxIterations(5)
        .onToolCall(tc -> System.out.println(">> Tool call: " + tc))
        .onObservation(obs -> System.out.println(">> Observation: " + obs))
        .build();

    ChatGeneration result = agent.invoke("帮我查一下我的公网 IP 是什么？");
    System.out.println(result.getText());
}
```

`.tools(mcpManager, "default")` reads `mcp.config.json`, converts the group into JSON Schema, and registers it with the LLM.

---

## 3. Sample Output

```
>> Tool call: get_export_ip
>> Observation: 123.117.177.40
```

Ask for “IP + weather” and the agent chains multiple ToolCalls automatically.

---

## 4. Comparison

| Item | Manual loop (Article 11) | `McpAgentExecutor` |
|------|-------------------------|--------------------|
| LOC | ~60 | ~10 |
| Loop control | Hand-written | Built-in |
| ToolCall parsing | Manual | Automatic |
| Debug hooks | Custom logging | `onToolCall` / `onObservation` |
| Use cases | Need custom logic inside loop | Standard tool execution |

Switch back to the manual loop when you must inject special behavior.

---

## 5. Builder Options

| Option | Required | Notes |
|--------|----------|-------|
| `llm(...)` | Yes | Any Function Calling model (Qwen 3.6+, GPT-4o, ...) |
| `tools(mcpManager, group)` | Yes | Load MCP tools by group |
| `systemPrompt(...)` | Yes | Define capabilities/constraints |
| `maxIterations(n)` | No | Default 5 |
| `onToolCall(...)` | No | Callback for telemetry |
| `onObservation(...)` | No | Callback for tool results |

---

## 6. Tips

- **Write concrete system prompts.** State objectives and constraints explicitly.
- **Group tools by domain** in `mcp.config.json` so each agent only sees relevant tools.
- **Tune `maxIterations`** to “max expected ToolCalls + 2”.

---

## 7. Summary

`McpAgentExecutor` reduces the Function Calling boilerplate to a handful of lines without sacrificing flexibility. Tool maintenance stays in `mcp.config.json`; agent behavior stays in prompts.

The next article covers the same pattern for NPX MCP servers via `McpClient`.

---

> Sample: `Article12McpManagerAgent.java` (`mcpManagerAgent`) – requires Aliyun `qwen3.6-plus`.
