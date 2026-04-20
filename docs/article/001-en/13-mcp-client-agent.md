# McpAgentExecutor + McpClient: Let Agents Operate Filesystems and Databases

> **Tags**: `Java` `MCP` `NPX` `Agent` `j-langchain` `McpAgentExecutor` `filesystem` `database`  
> **Prerequisite**: [Integrate the MCP Tool Protocol in Java](08-mcp.md) → [McpAgentExecutor: Let Models Call HTTP Tools in a Few Lines](12-mcp-manager-agent.md)  
> **Audience**: Java developers who have configured NPX MCP servers and want an Agent to operate files, databases, and other local resources

---

## 1. Capabilities Beyond HTTP Tools

The previous article used `McpManager` to connect HTTP APIs to the Agent, letting the model autonomously query public IP, weather, and other network services. But there's a class of capabilities HTTP APIs can't cover:

- Reading log files, configuration files from the server
- Querying a local PostgreSQL database without writing JDBC
- Letting the Agent remember state across rounds (key-value storage)
- Operating GitHub repositories, running browser automation

These capabilities correspond to **NPX MCP servers** — independent processes maintained by the MCP team or community, started with a single `npx` command, exposing a standard MCP tool interface. Article 08 already showed how to connect to these servers with `McpClient`. This article combines that with `McpAgentExecutor` so the model can autonomously call these tools.

---

## 2. The Only Difference from the Previous Article

Look at the code first — the comparison is very clear.

**Previous article (HTTP tools):**
```java
McpAgentExecutor agent = McpAgentExecutor.builder(chainActor)
    .llm(...)
    .tools(mcpManager, "default")     // Load HTTP tools from mcp.config.json
    .systemPrompt("...")
    .build();
```

**This article (NPX MCP server):**
```java
McpAgentExecutor agent = McpAgentExecutor.builder(chainActor)
    .llm(...)
    .tools(mcpClient, "filesystem")   // Load NPX server tools from mcp.server.config.json
    .systemPrompt("...")
    .build();
```

Only one line changed: `tools(mcpManager, "default")` becomes `tools(mcpClient, "filesystem")`. Everything else — LLM, system prompt, `maxIterations`, callbacks — stays exactly the same. This is the design value of `McpAgentExecutor`: **the tool source can be swapped freely without touching the Agent-layer code**.

---

## 3. Configuration

`mcp.server.config.json` describes which NPX servers to start:

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

`McpClient` starts this process at Spring startup time under the alias `filesystem`. Once the server is running it exposes a standard set of tools: `list_directory`, `read_file`, `write_file`, `create_directory`, etc. `McpAgentExecutor` converts this tool list to a Function Calling Schema, registers it with the model, and lets the model autonomously decide which tools to call.

---

## 4. Full Code

```java
@Test
public void mcpClientAgent() {

    McpAgentExecutor agent = McpAgentExecutor.builder(chainActor)
        .llm(ChatAliyun.builder().model("qwen3.6-plus").temperature(0f).build())
        .tools(mcpClient, "filesystem")   // Load all tools from the filesystem server
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

## 5. Execution Result

```
>> Tool call: list_directory -> {"path": "/tmp"}
>> Observation: {"files": ["ticket_result.txt", "demo.log", "config.yaml", ...]}

=== 最终答案 ===
/tmp 目录下共有 8 个文件，包括 ticket_result.txt、demo.log、config.yaml 等。
```

After getting the `list_directory` result, the model counts the files directly and outputs the conclusion — only one tool call needed. For a more complex task like "read config.yaml and tell me the database address," the model automatically adds a `read_file` call without any extra code.

---

## 6. Swapping the Server: PostgreSQL

`filesystem` is just one of many NPX MCP servers. Switch the alias to `postgres` and the Agent can query a database directly — no JDBC code required:

Add to `mcp.server.config.json`:

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

Only one change in the Agent code:

```java
McpAgentExecutor agent = McpAgentExecutor.builder(chainActor)
    .llm(ChatAliyun.builder().model("qwen3.6-plus").temperature(0f).build())
    .tools(mcpClient, "postgres")     // Switch to the postgres server
    .systemPrompt("你是一个数据库助手，可以查询数据库中的表结构和数据。")
    .maxIterations(5)
    .build();

ChatGeneration result = agent.invoke("查询 orders 表中最近 5 条记录");
```

`@modelcontextprotocol/server-postgres` exposes tools including `query` (execute SQL), `list_tables` (list all tables), and `describe_table` (view table schema). The model automatically selects the appropriate tools, assembles the SQL, and returns the results.

---

## 7. Production Considerations

**Path safety**: the filesystem server can access the directory specified in its startup command. Always pass a restricted path (like `/tmp` or a dedicated working directory) — never pass the system root or a path containing sensitive files.

**Database permissions**: the database account in the postgres connection string should be granted only SELECT (for read-only scenarios) or the minimum permissions explicitly required, to prevent the Agent from accidentally executing destructive operations.

**Audit logging**: the `onToolCall` and `onObservation` callbacks aren't just for debugging in production. They can feed a logging system to record every file read/write or SQL execution, meeting compliance audit requirements.

**Concurrent sharing**: a single `McpClient` instance (corresponding to a single server process) can be shared by multiple Agents. If you need to isolate different Agents' access paths, configure multiple aliases in `mcp.server.config.json`, each pointing to the same server type with different parameters.

---

## 8. Summary

`McpAgentExecutor + McpClient` has almost exactly the same integration approach as the `McpManager` version in the previous article — near-zero additional learning cost. The only difference is the tool source: HTTP APIs use `McpManager`, NPX servers use `McpClient`.

When switching tool sources, only one line of Agent code changes. This means you can start with `McpManager` and HTTP tools to quickly validate business logic, then replace some tools with more stable NPX servers once the approach is confirmed — with extremely low migration cost.

If your scenario requires using HTTP tools and NPX servers simultaneously, the next article shows how to combine both in a single Agent.

---

> 📎 Resources
> - Full example: [Article13McpClientAgent.java](../../../src/test/java/org/salt/jlangchain/demo/article/Article13McpClientAgent.java), method `mcpClientAgent()`
> - Server config: [mcp.server.config.json](../../../src/test/resources/mcp.server.config.json)
> - Common NPX MCP servers: `@modelcontextprotocol/server-filesystem`, `server-postgres`, `server-memory`, `server-github`
> - j-langchain GitHub: https://github.com/flower-trees/j-langchain
> - j-langchain Gitee mirror: https://gitee.com/flower-trees-z/j-langchain
> - Runtime requirements: Aliyun API Key required, example model `qwen3.6-plus`; Node.js must be installed locally to run npx
