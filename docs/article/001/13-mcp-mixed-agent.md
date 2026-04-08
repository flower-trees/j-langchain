# McpAgentExecutor 混合模式：McpManager + McpClient

> **前置阅读**：[McpAgentExecutor + McpManager（HTTP API）](11-mcp-manager-agent.md)、[McpAgentExecutor + McpClient（NPX 服务器）](12-mcp-client-agent.md)  
> **适合人群**：单源 Agent 已跑通，需要 **一次用户任务** 内串联 **HTTP 工具** 与 **NPX MCP 工具**  
> **核心概念**：同一 `McpAgentExecutor` 多次 `.tools(...)`、工具名空间合并、跨来源多步任务  
> **配套代码**：`Article13McpMixedAgent.java`（`mcpMixedAgent()`）

---

## 场景

典型需求：**先调公网 HTTP 拿到数据，再写入本地文件并读回确认**。  
数据来源不同：前者在 `mcp.config.json`（`McpManager`），后者在 `mcp.server.config.json`（`McpClient` → filesystem）。

---

## 核心代码

```java
McpAgentExecutor agent = McpAgentExecutor.builder(chainActor)
    .llm(ChatAliyun.builder().model("qwen3.6-plus").temperature(0f).build())
    .tools(mcpManager, "default")
    .tools(mcpClient, "filesystem")
    .systemPrompt("你是一个智能助手，可以调用工具获取网络信息和操作文件系统。\n" +
                  "当你完成用户的所有要求后，直接给出最终答案，不要再调用任何工具。")
    .maxIterations(8)
    .onToolCall(tc -> System.out.println(">> Tool: " + tc))
    .onObservation(obs -> System.out.println(">> Result: " + obs))
    .build();

ChatGeneration result = agent.invoke(
    "帮我查一下公网 IP，然后把 IP 地址写入 /tmp/my_ip.txt 文件，读取文件内容确认写入成功");
```

- **链式 `.tools`**：先注册 HTTP 组，再注册 `filesystem`；最终 Tool 列表合并给同一模型。  
- **`maxIterations(8)`**：跨多工具、多步时适当加大上限。  
- **systemPrompt 收尾约束**：减少模型在任务已完成时仍反复调用工具的情况（可按业务再调）。

---

## 与双 Agent 链（`Article08Mcp.dualAgentChain`）的对比

| 方式 | 特点 |
|------|------|
| **混合 McpAgentExecutor（本篇）** | 一个 FC Agent、一套工具池；适合步骤仍属「同一执行平面」的任务 |
| **ReAct + McpAgentExecutor 串联** | 分析类与执行类分工、中间用 `TranslateHandler` 拼装 Prompt；见 `Article08Mcp` |

---

## 小结

| 要点 | 说明 |
|------|------|
| 配置 | 同时依赖 `mcp.config.json` 与 `mcp.server.config.json` |
| 环境 | HTTP + NPX + LLM Key，与文章 11、12 叠加 |
| 调试 | 善用 `onToolCall` / `onObservation` 区分来自哪一类工具 |

---

> 完整实现：`src/test/java/org/salt/jlangchain/demo/article/Article13McpMixedAgent.java`
