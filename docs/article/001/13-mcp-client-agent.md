# McpAgentExecutor + McpClient：NPX MCP 服务器接入

> **场景**：你已经能通过 `mcp.server.config.json` 启动官方/社区 MCP 服务器（filesystem、memory、postgres……），希望 Agent 自动选择这些工具执行任务。  
> **配套代码**：`src/test/java/org/salt/jlangchain/demo/article/Article13McpClientAgent.java`

与文章 12 不同，本篇不再是 HTTP API，而是**独立进程型** MCP：例如 `@modelcontextprotocol/server-filesystem`。`McpAgentExecutor` 只需改用 `tools(mcpClient, "filesystem")`，其余逻辑保持一致。

---

## 配置回顾

`mcp.server.config.json` 示例如下：

```json
{
  "mcpServers": {
    "filesystem": {
      "command": "npx",
      "args": ["-y", "@modelcontextprotocol/server-filesystem", "/tmp"],
      "env": {"NODE_ENV": "production"}
    }
  }
}
```

McpClient 在 Spring 启动时会根据别名 `filesystem` 拉起进程，后续 `McpAgentExecutor` 只需引用该别名。

---

## 代码要点

```java
McpAgentExecutor agent = McpAgentExecutor.builder(chainActor)
    .llm(ChatAliyun.builder().model("qwen3.6-plus").temperature(0f).build())
    .tools(mcpClient, "filesystem")          // 载入该服务器暴露的全部工具（list_directory、read_file...）
    .systemPrompt("你是一个文件管理助手，可以浏览和读取 /tmp 目录里的文件")
    .maxIterations(5)
    .onToolCall(tc -> System.out.println(">> Tool call: " + tc))
    .onObservation(obs -> System.out.println(">> Observation: " + obs))
    .build();

ChatGeneration result = agent.invoke("列出 /tmp 目录的文件，并告诉我数量");
```

这段代码与文章 12 的唯一差异，就是把工具来源从 `McpManager` 换成 `McpClient` + 服务器别名。其余配置（LLM、提示词、回调）完全相同。

---

## 运行示例

```
>> Tool call: list_directory -> {"path":"/tmp"}
>> Observation: {"files":["ticket_result.txt","demo.log",...]} 

=== 最终答案 ===
/tmp 目录共有 8 个文件，包含 ticket_result.txt、demo.log 等。
```

模型会根据系统提示调用 `list_directory`、`read_file`、`write_file` 等工具，你可以在提示词中进一步限定访问路径或输出格式。

---

## 最佳实践

| 主题 | 建议 |
|------|------|
| 安全 | NPX 服务器默认能访问传入的目录；生产环境务必限制路径或使用沙盒目录 |
| 并发 | 同一服务器可被多个 Agent 共享；如需隔离，可在 `mcp.server.config.json` 配多个别名 |
| 监控 | 使用 `onToolCall`/`onObservation` 记录每次文件操作，便于审计 |

---

## 延伸阅读

- 需要 HTTP + NPX 同时挂载、跨来源编排？请看 [文章 14](14-mcp-mixed-agent.md)。
- 想进一步将 ReAct Prompt 与 MCP 文件操作组合？可以把本文的 `tools(mcpClient, "filesystem")` 与文章 11/12 的思路结合，构建自定义工作流。
