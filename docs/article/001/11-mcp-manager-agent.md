# McpAgentExecutor + McpManager：HTTP API 工具接入 Agent

> **前置文章**：[在 Java AI 应用中集成 MCP 工具协议](08-mcp.md)（McpManager、`mcp.config.json`、清单与直接调用）  
> **适合人群**：已配置 HTTP 类 MCP 工具，希望用 **Function Calling** 让模型自动选工具、多轮调用的 Java 开发者  
> **核心概念**：`McpAgentExecutor`、`McpManager.toTools()`、阿里云等支持 tools 的聊天模型  
> **配套代码**：`Article11McpManagerAgent.java`（`mcpManagerAgent()`）

---

## 与文章 8 的关系

文章 8 侧重 **协议与基础设施**：如何声明工具、如何用 `mcpManager.run(...)` **手动**调用。

本篇侧重 **Agent 封装**：把 `McpManager` 某一工具组（如 `default`）转成 LLM 可用的 Tool 列表，由 **`McpAgentExecutor`** 驱动多轮「模型决策 → 执行工具 → 再生成」循环，无需手写 ReAct 文本解析。

---

## 运行条件

- **配置**：`src/test/resources/mcp.config.json` 中已注册 HTTP 工具（示例含 `get_export_ip` 等）。  
- **Spring**：测试类与 `Article08Mcp` 相同，加载 `TestApplication` + `JLangchainConfigTest`（注入 `McpManager`、`ChainActor`）。  
- **模型**：示例使用 `ChatAliyun` + `qwen3.6-plus`，需配置 `ALIYUN_KEY`。  
- **网络**：HTTP 工具会访问外网（如 ipinfo）。

---

## 核心代码

```java
McpAgentExecutor agent = McpAgentExecutor.builder(chainActor)
    .llm(ChatAliyun.builder().model("qwen3.6-plus").temperature(0f).build())
    .tools(mcpManager, "default")
    .systemPrompt("你是一个智能助手，可以调用工具获取信息后回答用户问题。")
    .maxIterations(5)
    .onToolCall(tc -> System.out.println(">> Tool call: " + tc))
    .onObservation(obs -> System.out.println(">> Observation: " + obs))
    .build();

ChatGeneration result = agent.invoke("帮我查一下我的公网 IP 是什么？");
```

- **`.tools(mcpManager, "default")`**：与 `mcp.config.json` 里顶层 key `default` 对应，将该组下所有 HTTP 工具注册进 Agent。  
- **`onToolCall` / `onObservation`**：便于调试每一轮结构化调用与工具返回值。

---

## 小结

| 要点 | 说明 |
|------|------|
| 适用场景 | 已有 REST/HTTP 封装的能力，希望统一走 MCP 清单 + Agent 自动调用 |
| 与 ReAct | `McpAgentExecutor` 走 **Function Calling**，不是文章 4/9 的 ReAct 文本协议 |
| 下一步 | 需要 **NPX 进程型** MCP（如文件系统）见 [文章 12](12-mcp-client-agent.md)；两者合并见 [文章 13](13-mcp-mixed-agent.md) |

---

> 完整实现：`src/test/java/org/salt/jlangchain/demo/article/Article11McpManagerAgent.java`
