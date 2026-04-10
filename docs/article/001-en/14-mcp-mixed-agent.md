# Mixing McpAgentExecutor Sources: HTTP Tools + NPX Servers in One Agent

> **Tags**: Java, MCP, Agent, j-langchain, Function Calling  
> **Prerequisites**: [HTTP tools](12-mcp-manager-agent.md) → [NPX tools](13-mcp-client-agent.md)

---

## 1. Why Mix?

Article 12 handled HTTP tools. Article 13 handled NPX servers. Real workflows often require both: fetch data via HTTP, then write files or update a database. Maintaining separate agents adds friction.

`McpAgentExecutor` allows chained `.tools(...)` calls so a single agent can load multiple tool sources.

---

## 2. Core Usage

```java
McpAgentExecutor agent = McpAgentExecutor.builder(chainActor)
    .llm(ChatAliyun.builder().model("qwen3.6-plus").temperature(0f).build())
    .tools(mcpManager, "default")    // HTTP tools
    .tools(mcpClient, "filesystem")  // NPX tools
    .systemPrompt("""
        你是一个智能助手，可以调用工具获取网络信息和操作文件系统。
        当你完成用户的所有要求后，直接给出最终答案，不要再调用任何工具。
        """)
    .maxIterations(8)
    .onToolCall(tc -> System.out.println(">> Tool: " + tc))
    .onObservation(obs -> System.out.println(">> Result: " + obs))
    .build();
```

If two tools share the same name, the later registration wins—avoid collisions by naming tools per domain.

---

## 3. Example Task

```
Invoke: “Get my public IP, write it to /tmp/my_ip.txt, then read the file to confirm.”

Tool sequence chosen by the model:
1. `get_export_ip` (HTTP)
2. `write_file` (filesystem server)
3. `read_file` (filesystem server)
```

The final answer confirms the write/read.

---

## 4. Tool Count vs. Accuracy

Too many tools reduce Function Calling accuracy. Keep each agent’s tool list focused (≈5–10). Split groups per domain in `mcp.config.json` / `mcp.server.config.json` and load only what the agent needs.

---

## 5. System Prompt Matters More

Explicit prompts work best:

```
You are a network diagnostics assistant.
Flow: (1) use HTTP tools to gather info, (2) use filesystem tools to persist results, (3) stop calling tools after finishing.
Each tool runs at most once.
```

This guidance minimizes redundant calls and keeps `maxIterations` tight.

---

## 6. maxIterations Guidelines

Estimate worst-case ToolCalls and add ~2. For the IP→file example: 3 calls → set 8. Too large wastes tokens when loops repeat.

---

## 7. Summary

| Source | Config | Builder call |
|--------|--------|--------------|
| HTTP API | `mcp.config.json` | `.tools(mcpManager, group)` |
| NPX server | `mcp.server.config.json` | `.tools(mcpClient, alias)` |
| Mixed | Both | Chain `.tools(...)` |

Mixing sources requires no new abstractions; simply register both lists. Keep tool groups focused and prompts specific.

---

> Sample: `Article14McpMixedAgent.java` (`mcpMixedAgent`) – requires Aliyun `qwen3.6-plus` and Node.js for NPX.
