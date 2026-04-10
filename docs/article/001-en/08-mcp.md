# Integrating MCP Tools in Java Agents: Let AI Drive Real Systems

> **Tags**: Java, MCP, Agent, j-langchain, LLM, Tool Use, Function Calling  
> **Audience**: Developers who want to plug enterprise APIs, databases, and filesystems into Java agents

---

## 1. The Pain Point

After building a few AI prototypes, every team hits the same limit:

> The model is smart but blind. Fetching inventory? Write a tool. Weather? Another tool. A new system? Rewrite everything. Tools pile up, each agent reinvents the wheel.

The root cause is the lack of a **standard** tool protocol. Anthropic’s **Model Context Protocol (MCP)** (2024) fixes this by:

- Describing tool capabilities via a unified schema
- Shipping official and community servers you can reuse
- Declaring tools once so every agent can consume them

This article shows how to integrate MCP in j-langchain using two approaches and how to wire the whole tool-call loop end to end.

---

## 2. Two Integration Paths

| Path | Core class | Use cases |
|------|------------|-----------|
| HTTP APIs → MCP tools | `McpManager` | Enterprise REST APIs, third-party HTTP services |
| NPX MCP servers | `McpClient` | Filesystem, database, GitHub, browser automation, etc. |

Use both: `McpManager` for your APIs, `McpClient` for community servers.

---

## 3. McpManager: Turn HTTP APIs into MCP Tools

### Flow

`McpManager` loads `mcp.config.json`, registers each HTTP endpoint as an MCP tool, and exposes:

```
manifest()          → prompt-friendly descriptions
manifestForInput()  → JSON Schema for Function Calling models
run()/runForInput() → real HTTP execution
```

### Config Example

```json
{
  "default": [
    {
      "name": "get_export_ip",
      "description": "获取当前网络的公网出口 IP",
      "url": "http://ipinfo.io/ip",
      "method": "GET",
      "params": {}
    },
    {
      "name": "query_weather",
      "description": "查询指定城市的实时天气",
      "url": "https://api.example.com/weather",
      "method": "POST",
      "params": {
        "city": {
          "description": "城市名称，如：上海",
          "type": "string"
        },
        "required": ["city"]
      }
    }
  ]
}
```

`default` is a group; split by domain if needed.

### Usage

```java
@Bean
public McpManager mcpManager() throws Exception {
    return new McpManager("mcp.config.json");
}

@Test
public void mcpManagerManifest() {
    System.out.println(JsonUtil.toJson(mcpManager.manifest()));
    System.out.println(JsonUtil.toJson(mcpManager.manifestForInput()));
}

@Test
public void mcpManagerRun() throws Exception {
    Object result = mcpManager.run("default", "get_export_ip", Map.of());
    System.out.println(JsonUtil.toJson(result));

    Object inputResult = mcpManager.runForInput("default", "get_export_ip", Map.of());
    System.out.println(JsonUtil.toJson(inputResult));
}
```

---

## 4. McpClient: Connect to NPX MCP Servers

### Why

Not everything is a REST API. File IO, SQL queries, Git operations, browser automation—writing bespoke wrappers is tedious. MCP already provides **official servers** for these capabilities; launch them with a single `npx` command and skip server-side coding.

| Server | Capability | Use case |
|--------|------------|----------|
| `@modelcontextprotocol/server-filesystem` | Read/write directories | Agents read configs, write reports |
| `.../server-memory` | KV store | Agent memory across turns |
| `.../server-postgres` | PostgreSQL queries | BI dashboards |
| `.../server-github` | GitHub automation | Review bots |
| `.../server-puppeteer` | Browser automation | Screenshots, form filling |
| `.../server-brave-search` | Web search | Real-time info |

### Config Example

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

### Usage

```java
@Bean
public McpClient mcpClient() {
    return new McpClient("mcp.server.config.json");
}

@Test
public void mcpClientListTools() {
    System.out.println(JsonUtil.toJson(mcpClient.listAllTools()));
}

@Test
public void mcpMemoryServerConnect() throws Exception {
    ServerConfig config = new ServerConfig();
    config.command = "npx";
    config.args = List.of("-y", "@modelcontextprotocol/server-memory");
    config.env = new HashMap<>();

    McpServerConnection connection = new McpServerConnection("memory-server", config);
    connection.connect();

    System.out.println(JsonUtil.toJson(connection.listTools()));
    Object result = connection.callTool("search_nodes", new HashMap<>());
    System.out.println(JsonUtil.toJson(result));
}
```

PostgreSQL is identical—just change the server package and connection string:

```java
config.args = List.of(
    "-y",
    "@modelcontextprotocol/server-postgres",
    "postgresql://user:password@localhost:5432/mydb"
);
```

Now an agent can run SQL via MCP without touching JDBC.

---

## 5. Wire Tools into the LLM

Once tools are registered, feed them to the LLM so it can decide when/what to call.

### Flow

```
Question
  ↓
manifestForInput() → JSON schema
  ↓
LLM (Function Calling) → ToolCall
  ↓
mcpManager.run(...)   → real HTTP request
  ↓
Observation → final answer
```

### Code

```java
@Test
public void mcpLlmDemo() {
    List<AiChatInput.Tool> tools = mcpManager.manifestForInput().get("default");

    BaseRunnable<ChatPromptValue, ?> prompt = ChatPromptTemplate.fromMessages(
        List.of(
            BaseMessage.fromMessage(MessageType.SYSTEM.getCode(),
                "你是一个 AI 助手，可以调用 tools 中的工具回答用户问题。"),
            BaseMessage.fromMessage(MessageType.HUMAN.getCode(),
                "用户问题：${input}")
        )
    );

    ChatAliyun llm = ChatAliyun.builder()
        .model("qwen3.6-plus")
        .temperature(0f)
        .tools(tools)
        .build();

    FlowInstance chain = chainActor.builder()
        .next(prompt)
        .next(llm)
        .build();

    ToolMessage result = chainActor.invoke(chain, Map.of("input", "告诉我当前的公网 IP"));
    System.out.println(result.getToolCalls());

    // iterate toolCalls and mcpManager.run(...)
}
```

Sample ToolCall:

```json
[{ "name": "get_export_ip", "arguments": {} }]
```

Call `mcpManager.run(...)` with that payload, inject the observation back into the conversation, and the model produces the final answer. Tools are declared once and reused everywhere.

---

## 6. Project Layout

```
src/test/resources/
├── mcp.config.json
└── mcp.server.config.json

src/test/java/
└── Article08Mcp.java
```

Beans:

```java
@Bean
public McpManager mcpManager() throws Exception { ... }

@Bean
public McpClient mcpClient() { ... }
```

---

## 7. Summary

| Scenario | Recommendation | Class |
|----------|----------------|-------|
| Expose enterprise REST APIs | HTTP → MCP | `McpManager` |
| Use filesystem/DB/GitHub/etc. | NPX server | `McpClient` |
| Let the LLM auto-select tools | Function Calling | `manifestForInput()` + tools-enabled LLM |
| Fine-grained server control | Direct connection | `McpServerConnection` |

**Declare tools once, reuse everywhere, and leverage the MCP ecosystem instead of rebuilding utilities.**

---

> Resources: GitHub mirror links, MCP spec, and full sample in `Article08Mcp.java`.
