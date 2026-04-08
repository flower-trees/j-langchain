# McpAgentExecutor + McpClient：NPX MCP 服务器（如文件系统）

> **前置文章**：[在 Java AI 应用中集成 MCP 工具协议](08-mcp.md)（McpClient、`mcp.server.config.json`、列出工具）  
> **适合人群**：已能启动 NPX MCP 服务器，希望 Agent **自动调用** 其暴露的工具（列表目录、读文件等）  
> **核心概念**：`McpAgentExecutor`、`McpClient`、按服务器别名注册工具  
> **配套代码**：`Article12McpClientAgent.java`（`mcpClientAgent()`）

---

## 与文章 8 的关系

文章 8 展示 `mcpClient.listAllTools()`、`callTool(...)` 等 **命令式** 用法。

本篇用 **`McpAgentExecutor.builder(...).tools(mcpClient, "filesystem")`**，把名为 `filesystem` 的 NPX 服务器上的工具全部交给模型，在 **多轮对话** 中由模型决定调用哪个工具、传什么参数。

---

## 运行条件

- **Node.js / NPX**：`mcp.server.config.json` 里 `filesystem` 等条目会通过 `npx` 拉取官方 MCP 包并启动进程。  
- **路径**：示例配置常为 `@modelcontextprotocol/server-filesystem` + 根目录（如 `/tmp`）；Prompt 中应让模型操作该目录。  
- **模型与 Key**：同文章 11，`qwen3.6-plus` + `ALIYUN_KEY`。

---

## 核心代码

```java
McpAgentExecutor agent = McpAgentExecutor.builder(chainActor)
    .llm(ChatAliyun.builder().model("qwen3.6-plus").temperature(0f).build())
    .tools(mcpClient, "filesystem")
    .systemPrompt("你是一个文件管理助手，可以浏览和读取 /tmp 目录中的文件。")
    .maxIterations(5)
    .onToolCall(tc -> System.out.println(">> Tool call: " + tc))
    .onObservation(obs -> System.out.println(">> Observation: " + obs))
    .build();

ChatGeneration result = agent.invoke("列出 /tmp 目录下的所有文件，并告诉我有多少个文件");
```

- **`.tools(mcpClient, "filesystem")`**：与 `mcp.server.config.json` 中 `mcpServers.filesystem` 对应。  
- 若配置多个服务器，可对不同别名多次 `.tools(mcpClient, "别名")`，或与 `McpManager` 混用（见文章 13）。

---

## 小结

| 要点 | 说明 |
|------|------|
| 适用场景 | 文件、Memory、数据库等 **官方/社区 MCP 进程**，希望交给 Agent 自动编排 |
| 注意 | 进程启动与权限与本地环境相关，CI 中常跳过或 mock |
| 下一步 | [文章 13](13-mcp-mixed-agent.md)：HTTP 工具 + NPX 工具 **同一 Agent** |

---

> 完整实现：`src/test/java/org/salt/jlangchain/demo/article/Article12McpClientAgent.java`
