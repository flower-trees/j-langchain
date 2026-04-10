# Integrating MCP Tools in Java Agents: Let AI Drive Real Enterprise Systems

> **Tags**: `Java` `MCP` `Agent` `j-langchain` `LLM` `Tool Use` `Function Calling`  
> **Audience**: Developers who want to unify enterprise APIs, databases, and filesystems as tools in a Java Agent

---

## I. Where the Problem Starts

As AI application development matures, nearly every team hits the same bottleneck:

> The model itself is smart, but it can't touch anything. Querying inventory requires wrapping a Tool, querying weather requires another Tool, integrating a new system means rewriting everything... the tool count keeps growing, and every Agent reinvents the wheel.

The root cause is **no unified standard at the tool layer**. **MCP (Model Context Protocol)**, introduced by Anthropic in 2024, was designed to solve exactly this:

- Describe tool capabilities with a unified protocol
- A large and growing official + community ecosystem of plug-and-play servers
- Declare a tool once; every Agent can reuse it

This article explains how to integrate MCP in j-langchain, covering both integration approaches and the full pipeline from tool registration to automatic LLM invocation.

---

## II. Two Integration Approaches Covering the Vast Majority of Use Cases

j-langchain provides two MCP integration methods for different tool sources:

| Approach | Core class | Use case |
|----------|-----------|----------|
| HTTP API → MCP tool | `McpManager` | Internal REST APIs, third-party HTTP services |
| NPX MCP server | `McpClient` | Filesystem, database, GitHub, browser automation, etc. |

The two approaches can be mixed: use `McpManager` for your own APIs and `McpClient` for community ecosystem servers.

---

## III. Approach 1: Turn HTTP APIs into MCP Tools with McpManager

### How It Works

`McpManager` reads an `mcp.config.json` configuration file and automatically registers each HTTP endpoint described there as an MCP tool. The model can then call them through the standard Function Calling interface.

The overall flow:

```
mcp.config.json (tool descriptions)
       ↓
McpManager (load & manage)
       ↓
manifest()          → Tool manifest (inject into Prompt so the model knows what tools are available)
manifestForInput()  → JSON Schema (give to the model to enable Function Calling)
run() / runForInput() → Actually execute the HTTP request
```

### Configuration File Example

`mcp.config.json` is a tool menu — each entry describes one HTTP endpoint:

```json
{
  "default": [
    {
      "name": "get_export_ip",
      "description": "Get the public outbound IP of the current network",
      "url": "http://ipinfo.io/ip",
      "method": "GET",
      "params": {}
    },
    {
      "name": "query_weather",
      "description": "Query real-time weather for a specified city",
      "url": "https://api.example.com/weather",
      "method": "POST",
      "params": {
        "city": {
          "description": "City name, e.g.: Shanghai",
          "type": "string"
        },
        "required": ["city"]
      }
    }
  ]
}
```

> `default` is the group name. You can split tools into multiple groups by business module, and Agents load only the groups they need.

### Code Example

Registering the bean takes only two lines:

```java
@Bean
public McpManager mcpManager() throws Exception {
    return new McpManager("mcp.config.json");
}
```

View the tool manifest:

```java
@Test
public void mcpManagerManifest() {
    // Text description (can be injected into a Prompt so the model knows what tools are available)
    System.out.println(JsonUtil.toJson(mcpManager.manifest()));

    // JSON Schema version (pass directly to a model that supports Function Calling)
    System.out.println(JsonUtil.toJson(mcpManager.manifestForInput()));
}
```

Call a tool directly to verify the endpoint is working:

```java
@Test
public void mcpManagerRun() throws Exception {
    // Direct call — returns the raw result
    Object result = mcpManager.run("default", "get_export_ip", Map.of());
    System.out.println("API response: " + JsonUtil.toJson(result));

    // Returns LLM-formatted input (convenient for injecting into Observation)
    Object inputResult = mcpManager.runForInput("default", "get_export_ip", Map.of());
    System.out.println("LLM format: " + JsonUtil.toJson(inputResult));
}
```

---

## IV. Approach 2: Connect to NPX MCP Servers with McpClient

### Why This Approach Is Needed

Not all capabilities can be wrapped as HTTP APIs. File read/write, SQL queries, Git operations, browser automation — wrapping all of these yourself is a huge amount of work.

The MCP ecosystem already provides a set of **official standard servers**, each covering a category of capabilities, launched with a single `npx` command — no server code to write yourself.

### Common MCP Servers Quick Reference

| Server package | Capability | Typical use |
|---|---|---|
| `@modelcontextprotocol/server-filesystem` | Local/mounted directory read-write | Agent reads configs, writes reports |
| `@modelcontextprotocol/server-memory` | KV key-value storage | Agent cross-turn memory |
| `@modelcontextprotocol/server-postgres` | PostgreSQL queries | Automated data reports, intelligent BI |
| `@modelcontextprotocol/server-github` | Issue/PR/repository operations | Code review Agent, auto-archiving |
| `@modelcontextprotocol/server-puppeteer` | Browser automation | Screenshots, form filling |
| `@modelcontextprotocol/server-brave-search` | Web search | Real-time information retrieval |

### Configuration File Example

`mcp.server.config.json` describes which servers to start and what arguments to pass:

```json
{
  "mcpServers": {
    "filesystem": {
      "command": "npx",
      "args": ["-y", "@modelcontextprotocol/server-filesystem", "/tmp"],
      "env": { "NODE_ENV": "production" }
    },
    "memory": {
      "command": "npx",
      "args": ["-y", "@modelcontextprotocol/server-memory"],
      "env": {}
    }
  }
}
```

### Code Example

Register the bean:

```java
@Bean
public McpClient mcpClient() {
    return new McpClient("mcp.server.config.json");
}
```

List the tool manifest for all servers at once:

```java
@Test
public void mcpClientListTools() {
    // List all configured servers and their tools
    System.out.println(JsonUtil.toJson(mcpClient.listAllTools()));
}
```

For fine-grained control of a specific server, use `McpServerConnection` directly:

```java
@Test
public void mcpMemoryServerConnect() throws Exception {
    ServerConfig config = new ServerConfig();
    config.command = "npx";
    config.args = List.of("-y", "@modelcontextprotocol/server-memory");
    config.env = new HashMap<>();

    McpServerConnection connection = new McpServerConnection("memory-server", config);
    connection.connect();

    System.out.println("Connected: " + connection.isConnected());
    System.out.println("Available tools: " + JsonUtil.toJson(connection.listTools()));

    // Call a specific tool
    Object result = connection.callTool("search_nodes", new HashMap<>());
    System.out.println("Query result: " + JsonUtil.toJson(result));
}
```

Connecting to PostgreSQL is the same — just change the server package name. **Agents no longer need to write JDBC**:

```java
@Test
public void mcpPostgresConnect() throws Exception {
    ServerConfig config = new ServerConfig();
    config.command = "npx";
    config.args = List.of(
        "-y",
        "@modelcontextprotocol/server-postgres",
        "postgresql://user:password@localhost:5432/mydb"  // Replace with your actual connection string
    );
    config.env = new HashMap<>();

    McpServerConnection connection = new McpServerConnection("postgres-server", config);
    connection.connect();

    System.out.println("Available tools: " + JsonUtil.toJson(connection.listTools()));
    // With MCP, the Agent executes SQL directly — no JDBC code needed
    // connection.callTool("query", Map.of("sql", "SELECT * FROM orders LIMIT 10"));
}
```

---

## V. Handing Tools to the Model: From Registration to Automatic Invocation

The previous two steps completed **tool registration**. This step gives the tools to the LLM so the model can decide when to call them and which one to call.

### Complete Pipeline

```
User question
   ↓
mcpManager.manifestForInput()  → Get the tools' JSON Schema
   ↓
Inject into ChatAliyun (a model that supports Function Calling)
   ↓
Model reasons, returns a ToolCall (tool name + arguments)
   ↓
mcpManager.run(...)            → Execute the real HTTP request
   ↓
Append result to the conversation; model generates the final answer
```

### Code Implementation

```java
@Test
public void mcpLlmDemo() {

    // 1. Get the MCP tools' JSON Schema (the model needs this format to call them automatically)
    List<AiChatInput.Tool> tools = mcpManager.manifestForInput().get("default");

    // 2. Build the Prompt template
    BaseRunnable<ChatPromptValue, ?> prompt = ChatPromptTemplate.fromMessages(
        List.of(
            BaseMessage.fromMessage(
                MessageType.SYSTEM.getCode(),
                "You are an AI assistant. Use the tools provided to answer user questions."
            ),
            BaseMessage.fromMessage(
                MessageType.HUMAN.getCode(),
                "User question: ${input}"
            )
        )
    );

    // 3. Configure the LLM with the tool manifest
    ChatAliyun llm = ChatAliyun.builder()
        .model("qwen3.6-plus")
        .temperature(0f)
        .tools(tools)   // Register MCP tools directly with the model
        .build();

    // 4. Build the chain
    FlowInstance chain = chainActor.builder()
        .next(prompt)
        .next(llm)
        .build();

    // 5. Execute; the model returns a ToolCall (it decides which tool to call and what arguments to pass)
    ToolMessage result = chainActor.invoke(chain, Map.of("input", "Tell me the current public IP"));
    System.out.println("Model chose to call: " + result.getToolCalls());

    // 6. Intercept the ToolCall and execute it with McpManager
    // mcpManager.run("default", toolCall.getName(), toolCall.getArguments());
}
```

After running, the model returns a ToolCall like this:

```json
[{
  "name": "get_export_ip",
  "arguments": {}
}]
```

Take this ToolCall, call `mcpManager.run(...)` to make the real request, then append the result to the conversation and the model will give the final answer.

> **Key advantage**: Tools are declared once in `mcp.config.json`. No matter how many Agents or chain paths you have, they all reuse this single configuration — no repeated wrapping.

---

## VI. Project Structure Reference

```
src/test/resources/
├── mcp.config.json           # HTTP API tool declarations
└── mcp.server.config.json    # NPX MCP server declarations

src/test/java/
└── Article08Mcp.java         # Complete sample code for this article
```

Spring bean registration (can be done in test config or application config):

```java
@Bean
public McpManager mcpManager() throws Exception {
    return new McpManager("mcp.config.json");
}

@Bean
public McpClient mcpClient() {
    return new McpClient("mcp.server.config.json");
}
```

---

## VII. Summary

| Scenario | Recommended approach | Core class |
|----------|---------------------|-----------|
| Integrate internal REST APIs | HTTP → MCP | `McpManager` + `mcp.config.json` |
| Integrate filesystem, database, GitHub, etc. | NPX MCP server | `McpClient` + `mcp.server.config.json` |
| Let the model auto-decide which tool to call | Function Calling | `manifestForInput()` + tools-capable LLM |
| Fine-grained control of a single server | Direct server connection | `McpServerConnection` |

The core value MCP brings in one sentence: **declare tools once, every Agent reuses them, and the ecosystem's capabilities are plug-and-play — no reinventing the wheel.**

---

> Related resources
> - j-langchain GitHub: https://github.com/flower-trees/j-langchain
> - j-langchain Gitee mirror: https://gitee.com/flower-trees-z/j-langchain
> - MCP official spec: https://modelcontextprotocol.io
> - Full sample code: [Article08Mcp.java](../../../src/test/java/org/salt/jlangchain/demo/article/Article08Mcp.java)
