# McpAgentExecutor + McpClient: Agents Operating Filesystems and Databases

> **Tags**: Java, MCP, NPX, Agent, j-langchain  
> **Prerequisites**: [MCP integration](08-mcp.md) → [McpAgentExecutor for HTTP tools](12-mcp-manager-agent.md)

---

## 1. Beyond HTTP APIs

`McpManager` exposes REST endpoints, but many capabilities aren’t HTTP-friendly:

- Read server log/config files
- Query PostgreSQL without writing JDBC
- Persist state between turns (KV store)
- Interact with GitHub, drive browsers, etc.

These are provided by **NPX MCP servers**—standalone processes launched via `npx`. Article 08 showed how to connect with `McpClient`; this article combines it with `McpAgentExecutor` so the model can call them autonomously.

---

## 2. Only One Line Changes

```java
// Previous article (HTTP tools)
.tools(mcpManager, "default")

// This article (NPX server)
.tools(mcpClient, "filesystem")
```

Everything else—LLM, system prompt, callbacks—stays the same. Tool sources are swappable.

---

## 3. Config

`mcp.server.config.json`:

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

`McpClient` launches this process, receives tools like `list_directory`, `read_file`, `write_file`, and hands them to `McpAgentExecutor`.

---

## 4. Code

```java
@Test
public void mcpClientAgent() {
    McpAgentExecutor agent = McpAgentExecutor.builder(chainActor)
        .llm(ChatAliyun.builder().model("qwen3.6-plus").temperature(0f).build())
        .tools(mcpClient, "filesystem")
        .systemPrompt("""
            你是一个文件管理助手，可以浏览和读取 /tmp 目录中的文件。
            请直接执行操作，不要询问用户额外确认。
            """)
        .maxIterations(5)
        .onToolCall(tc -> System.out.println(">> Tool call: " + tc))
        .onObservation(obs -> System.out.println(">> Observation: " + obs))
        .build();

    ChatGeneration result = agent.invoke("列出 /tmp 目录下的所有文件，并告诉我有多少个文件");
    System.out.println(result.getText());
}
```

---

## 5. Output

```
>> Tool call: list_directory
>> Observation: {"files": ["ticket_result.txt", "demo.log", ...]}

/tmp has 8 files including ticket_result.txt, demo.log, config.yaml, ...
```

For “read config.yaml and show the DB host”, the model would add a `read_file` ToolCall automatically.

---

## 6. Switching to PostgreSQL

Add another server entry:

```json
"postgres": {
  "command": "npx",
  "args": ["-y", "@modelcontextprotocol/server-postgres",
           "postgresql://user:password@localhost:5432/mydb"],
  "env": {}
}
```

Then:

```java
.tools(mcpClient, "postgres")
.systemPrompt("你是一个数据库助手...")
```

The agent can now call `query`, `list_tables`, `describe_table` without JDBC.

---

## 7. Production Notes

- **Path safety**: limit filesystem servers to safe directories.
- **DB permissions**: use least-privilege accounts.
- **Auditing**: stream `onToolCall`/`onObservation` to your logging system.
- **Isolation**: spin up multiple server aliases with different parameters if agents need isolated access.

---

## 8. Summary

`McpAgentExecutor` works identically whether tools come from HTTP (`McpManager`) or NPX servers (`McpClient`). Switching sources is literally one line. Mix-and-match by loading different groups per agent.

Next: combine both sources in a single agent.

---

> Sample: `Article13McpClientAgent.java` (`mcpClientAgent`) – requires Aliyun `qwen3.6-plus` and Node.js for NPX.
