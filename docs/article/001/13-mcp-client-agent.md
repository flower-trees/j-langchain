# McpAgentExecutor + McpClient：让 Agent 直接操作文件系统和数据库

> **标签**：`Java` `MCP` `NPX` `Agent` `j-langchain` `McpAgentExecutor` `文件系统` `数据库`  
> **前置阅读**：[Java Agent 集成 MCP 工具协议](08-mcp.md) → [McpAgentExecutor：用几行代码让模型自主调用 HTTP 工具](12-mcp-manager-agent.md)  
> **适合人群**：已配置好 NPX MCP 服务器，希望 Agent 直接操作文件、数据库等本地资源的 Java 开发者

---

## 一、HTTP 工具之外的能力

上篇文章用 `McpManager` 把 HTTP API 接入了 Agent，模型能自主查询公网 IP、天气等网络服务。但有一类能力是 HTTP API 覆盖不了的：

- 读取服务器上的日志文件、配置文件
- 查询本地 PostgreSQL 数据库，不想写 JDBC
- 让 Agent 记住跨轮次的状态（键值存储）
- 操作 GitHub 仓库、执行浏览器自动化

这些能力对应的是 **NPX MCP 服务器**——由 MCP 官方或社区维护的独立进程，通过 `npx` 一行命令启动，暴露标准的 MCP 工具接口。文章 08 已经介绍了如何用 `McpClient` 连接这些服务器，本篇把它和 `McpAgentExecutor` 结合起来，让模型自主调用这些工具。

---

## 二、和上篇的唯一区别

先看代码，对比会非常直观。

**上篇（HTTP 工具）：**
```java
McpAgentExecutor agent = McpAgentExecutor.builder(chainActor)
    .llm(...)
    .tools(mcpManager, "default")     // 从 mcp.config.json 加载 HTTP 工具
    .systemPrompt("...")
    .build();
```

**本篇（NPX MCP 服务器）：**
```java
McpAgentExecutor agent = McpAgentExecutor.builder(chainActor)
    .llm(...)
    .tools(mcpClient, "filesystem")   // 从 mcp.server.config.json 加载 NPX 服务器工具
    .systemPrompt("...")
    .build();
```

只改了一行：把 `tools(mcpManager, "default")` 换成 `tools(mcpClient, "filesystem")`。其余所有配置——LLM、系统提示、`maxIterations`、回调——完全不变。这正是 `McpAgentExecutor` 设计的价值所在：**工具来源可以随意切换，Agent 层的代码不用跟着改**。

---

## 三、配置文件

`mcp.server.config.json` 描述要启动哪些 NPX 服务器：

```json
{
  "mcpServers": {
    "filesystem": {
      "command": "npx",
      "args": ["-y", "@modelcontextprotocol/server-filesystem", "/tmp"],
      "env": { "NODE_ENV": "production" }
    }
  }
}
```

`McpClient` 在 Spring 启动时会根据别名 `filesystem` 拉起这个进程，服务器启动后会向外暴露一组标准工具：`list_directory`、`read_file`、`write_file`、`create_directory` 等。`McpAgentExecutor` 拿到这份工具列表后，转成 Function Calling Schema 注册给模型，后续的工具选择和调用由模型自主完成。

---

## 四、完整代码

```java
@Test
public void mcpClientAgent() {

    McpAgentExecutor agent = McpAgentExecutor.builder(chainActor)
        .llm(ChatAliyun.builder().model("qwen3.6-plus").temperature(0f).build())
        .tools(mcpClient, "filesystem")   // 加载 filesystem 服务器的全部工具
        .systemPrompt("""
            你是一个文件管理助手，可以浏览和读取 /tmp 目录中的文件。
            请直接执行操作，不要询问用户额外确认。
            """)
        .maxIterations(5)
        .onToolCall(tc -> System.out.println(">> Tool call: " + tc))
        .onObservation(obs -> System.out.println(">> Observation: " + obs))
        .build();

    ChatGeneration result = agent.invoke("列出 /tmp 目录下的所有文件，并告诉我有多少个文件");

    System.out.println("\n=== 最终答案 ===");
    System.out.println(result.getText());
}
```

---

## 五、执行效果

```
>> Tool call: list_directory -> {"path": "/tmp"}
>> Observation: {"files": ["ticket_result.txt", "demo.log", "config.yaml", ...]}

=== 最终答案 ===
/tmp 目录下共有 8 个文件，包括 ticket_result.txt、demo.log、config.yaml 等。
```

模型拿到 `list_directory` 的返回值后，直接统计文件数量并输出结论，整个过程只需要一次工具调用。如果任务更复杂，比如"读取 config.yaml 并告诉我其中的数据库地址"，模型会自动追加一次 `read_file` 调用，不需要任何额外代码。

---

## 六、换一个服务器：PostgreSQL

filesystem 只是众多 NPX MCP 服务器中的一个。把别名换成 `postgres`，Agent 就能直接查询数据库，不用写任何 JDBC 代码：

在 `mcp.server.config.json` 中添加：

```json
{
  "mcpServers": {
    "filesystem": { ... },
    "postgres": {
      "command": "npx",
      "args": ["-y", "@modelcontextprotocol/server-postgres",
               "postgresql://user:password@localhost:5432/mydb"],
      "env": {}
    }
  }
}
```

Agent 代码只改一处：

```java
McpAgentExecutor agent = McpAgentExecutor.builder(chainActor)
    .llm(ChatAliyun.builder().model("qwen3.6-plus").temperature(0f).build())
    .tools(mcpClient, "postgres")     // 换成 postgres 服务器
    .systemPrompt("你是一个数据库助手，可以查询数据库中的表结构和数据。")
    .maxIterations(5)
    .build();

ChatGeneration result = agent.invoke("查询 orders 表中最近 5 条记录");
```

`@modelcontextprotocol/server-postgres` 暴露的工具包括 `query`（执行 SQL）、`list_tables`（列出所有表）、`describe_table`（查看表结构）。模型会根据问题自动选择合适的工具，拼装 SQL，返回结果。

---

## 七、生产环境注意事项

**路径安全**：filesystem 服务器默认能访问启动命令中指定的目录，务必传入受限路径（如 `/tmp` 或专用的工作目录），不要传入系统根目录或含敏感文件的路径。

**数据库权限**：postgres 服务器使用的连接串对应的数据库账号，应只赋予 SELECT 权限（只读场景）或明确需要的最小权限，避免 Agent 误执行破坏性操作。

**审计日志**：`onToolCall` 和 `onObservation` 两个回调在生产环境中不只是调试工具，可以接入日志系统，完整记录每次文件读写或 SQL 执行，满足合规审计的要求。

**并发共享**：同一个 `McpClient` 实例（对应同一个服务器进程）可以被多个 Agent 共享。如果需要隔离不同 Agent 的访问路径，可以在 `mcp.server.config.json` 中配置多个别名，分别指向不同参数的同类服务器。

---

## 八、总结

`McpAgentExecutor + McpClient` 的接入方式和上篇的 `McpManager` 版本几乎完全一样，学习成本接近零。区别只在于工具来源：HTTP API 用 `McpManager`，NPX 服务器用 `McpClient`。

切换工具来源时，Agent 层的代码只改一行。这意味着你可以先用 `McpManager` 接 HTTP 工具快速验证业务逻辑，确认效果后再把部分工具替换成更稳定的 NPX 服务器，整个迁移成本极低。

如果你的场景需要同时使用 HTTP 工具和 NPX 服务器，下一篇会介绍如何把两者合并到同一个 Agent 中。

---

> 📎 相关资源
> - 完整示例：[Article13McpClientAgent.java](../../../src/test/java/org/salt/jlangchain/demo/article/Article13McpClientAgent.java)，对应方法 `mcpClientAgent()`
> - 服务器配置：[mcp.server.config.json](../../../src/test/resources/mcp.server.config.json)
> - 常用 NPX MCP 服务器：`@modelcontextprotocol/server-filesystem`、`server-postgres`、`server-memory`、`server-github`
> - j-langchain GitHub：https://github.com/flower-trees/j-langchain
> - j-langchain Gitee 镜像：https://gitee.com/flower-trees-z/j-langchain
> - 运行环境：需配置阿里云 API Key，示例模型 `qwen3.6-plus`；需本地安装 Node.js 以运行 npx