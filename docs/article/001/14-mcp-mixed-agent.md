# McpAgentExecutor 混合挂载：HTTP + NPX 同时接入

> **场景**：一个 Agent 既要调用 HTTP API（如 IP/天气），又要操作本地文件或数据库（filesystem、memory、postgres）——希望在一次多轮对话中串联完成。  
> **配套代码**：`src/test/java/org/salt/jlangchain/demo/article/Article14McpMixedAgent.java`

`McpAgentExecutor` 支持链式 `.tools(...)` 调用：先注册 `McpManager` 的 HTTP 工具，再注册 `McpClient` 的 NPX 服务器。最终工具列表会合并交给同一个模型使用。本例模拟“先查公网 IP，再把结果写入文件系统”的客服运维需求。

---

## 组合方式

```java
McpAgentExecutor agent = McpAgentExecutor.builder(chainActor)
    .llm(ChatAliyun.builder().model("qwen3.6-plus").temperature(0f).build())
    .tools(mcpManager, "default")        // HTTP 工具，例如 get_export_ip / get_ip_location
    .tools(mcpClient, "filesystem")      // NPX filesystem 工具，用于写入/读取记录
    .systemPrompt("先使用 HTTP 工具获取信息，再根据需求读写 /tmp 文件，完成即可停止")
    .maxIterations(8)
    .onToolCall(tc -> System.out.println(">> Tool: " + tc))
    .onObservation(obs -> System.out.println(">> Result: " + obs))
    .build();
```

- 注册顺序不限；可以多次调用 `.tools(...)`；
- 如果两个来源中存在同名工具，后注册的会覆盖前一个（建议按业务拆分命名）。

---

## 示例流程

```
>> Tool: get_export_ip -> {}
>> Result: 123.117.177.40
>> Tool: write_file -> {"path":"/tmp/ip.txt","content":"123.117.177.40"}
>> Result: 写入成功
>> Tool: read_file -> {"path":"/tmp/ip.txt"}
>> Result: 123.117.177.40

=== 最终答案 ===
公网 IP 已记录在 /tmp/ip.txt，内容为 123.117.177.40。
```

模型可以自由在 HTTP 和 NPX 工具间切换：例如先查 IP，再写文件，再读取确认，最后给出结果。系统提示可约束执行顺序、终止条件等。

---

## 设计建议

| 主题 | 建议 |
|------|------|
| 工具分组 | 将 HTTP 工具按域拆分成多个组（default、ops、crm），按需 `.tools(mcpManager, group)`；NPX 服务器也可配置多个别名（filesystem、memory-db...） |
| 提示词 | 明确“先做网络查询，再执行文件操作，完成后停止”之类的约束，避免模型在工具间来回无意义调用 |
| 日志 | 通过 `onToolCall`/`onObservation` 打印来源，方便定位是 HTTP 还是 NPX 工具带来的问题 |

---

## 与其它文章的关系

- HTTP-only 场景：请看 [文章 12](12-mcp-manager-agent.md)。
- NPX-only 场景：请看 [文章 13](13-mcp-client-agent.md)。
- 如果要在此基础上嵌套其它 Agent（如客服工单分析 + filesystem 执行），可参考 `Article16CustomerService`，它就是混合 MCP + 双 Agent 的落地案例。
