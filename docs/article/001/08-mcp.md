# 在 Java AI 应用中集成 MCP 工具协议

> **适用人群**：希望把 HTTP API 或 NPX MCP 服务器接入 Java Agent、既能直接调用也能交给模型使用的开发者。  
> **配套代码**：`src/test/java/org/salt/jlangchain/demo/article/Article08Mcp.java`

`Article08Mcp` 展示了基于 j-langchain 的 MCP 集成方式：

1. **McpManager** —— 读取 `mcp.config.json` 中声明的 HTTP 工具，输出清单并直接调用；
2. **McpClient** —— 读取 `mcp.server.config.json`，列出/调用 NPX 进程型 MCP 服务器（filesystem、memory、postgres 等）；
3. 为后续文章（11～14）里的 `McpAgentExecutor` 做准备：先把工具声明好、确认能独立运行。

---

## HTTP 工具（McpManager）

### 1. 查看清单（`mcpManagerManifest`）

```java
System.out.println(JsonUtil.toJson(mcpManager.manifest()));        // 简版，供 Prompt 注入
System.out.println(JsonUtil.toJson(mcpManager.manifestForInput())); // 含 JSON Schema，供 Function Calling
```

输出即 `mcp.config.json` 中各组工具的描述、参数（含 required）。`manifestForInput()` 会自动转换成 `AiChatInput.Tool`，后面可以直接交给支持 Function Calling 的模型。

### 2. 直接调用（`mcpManagerRun`）

```java
Object result = mcpManager.run("default", "get_export_ip", Map.of());
Object inputResult = mcpManager.runForInput("default", "get_export_ip", Map.of());
```

- `run(...)`：真正请求 HTTP API（GET/POST/PUT/PATCH），返回原始响应；
- `runForInput(...)`：把响应包装成模型更易读取的文本/JSON（常用于 ReAct 场景）。

配置示例（`src/test/resources/mcp.config.json`）：

```json
{
  "default": [
    {"name": "get_export_ip", "url": "http://ipinfo.io/ip", "method": "GET"},
    {"name": "get_ip_location", "url": "https://ipapi.com/ip_api.php", "method": "GET"}
  ]
}
```

---

## NPX MCP 服务器（McpClient）

`mcp.server.config.json` 指定 NPX 命令、参数、环境变量，`McpClient` 根据别名启动并复用这些进程。

### 1. 列出工具（`mcpClientListTools`）

```java
System.out.println(JsonUtil.toJson(mcpClient.listAllTools()));
```

当配置了多个服务器（如 filesystem、memory），输出会按别名聚合每个服务器的可用工具。

### 2. Memory 服务器示例（`mcpMemoryServerConnect`）

```java
ServerConfig config = new ServerConfig();
config.command = "npx";
config.args = List.of("-y", "@modelcontextprotocol/server-memory");
McpServerConnection connection = new McpServerConnection("memory-server", config);
connection.connect();
System.out.println(JsonUtil.toJson(connection.listTools()));
System.out.println(connection.callTool("search_nodes", new HashMap<>()));
```

该示例演示如何在代码里直接控制 NPX 进程、列出工具、调用 `search_nodes` 等接口，适用于需要细粒度管控生命周期的场景。

### 3. PostgreSQL 服务器示例（`mcpPostgresConnect`）

```java
ServerConfig config = new ServerConfig();
config.command = "npx";
config.args = List.of(
    "-y",
    "@modelcontextprotocol/server-postgres",
    "postgresql://myuser:123456@localhost:5432/mydb"
);
McpServerConnection connection = new McpServerConnection("postgres-server", config);
connection.connect();
System.out.println(JsonUtil.toJson(connection.listTools()));
```

连接成功后即可通过 MCP 标准工具（如 `query`）让 Agent 执行 SQL，无需手写 JDBC。

---

## 与 Agent 结合

在具备上述能力后，可进一步把工具交给模型自动决策：

- **纯 HTTP 工具 → `McpAgentExecutor`**：见 [文章 12](12-mcp-manager-agent.md)（`Article12McpManagerAgent`）。
- **纯 NPX 服务器 → `McpAgentExecutor`**：见 [文章 13](13-mcp-client-agent.md)（`Article13McpClientAgent`）。
- **HTTP + NPX 混合**：见 [文章 14](14-mcp-mixed-agent.md)。
- **Function Calling ReAct（手工链）**：见 [文章 11](11-mcp-react-agent.md)，演示 `manifestForInput()` 与模型 tools 能力的结合。
- **客服双 Agent**：完整流程（分析投诉 + filesystem 执行）已迁至 `Article16CustomerService`。

---

## 常用 MCP 服务器

| 服务器 | NPX 命令 | 功能 |
|--------|----------|------|
| filesystem | `@modelcontextprotocol/server-filesystem` | 读写本地文件 |
| memory | `@modelcontextprotocol/server-memory` | 持久化 KV（Agent 记忆） |
| postgres | `@modelcontextprotocol/server-postgres` | SQL 查询 |
| github | `@modelcontextprotocol/server-github` | GitHub 仓库操作 |
| puppeteer | `@modelcontextprotocol/server-puppeteer` | 浏览器控制、截图 |
| brave-search | `@modelcontextprotocol/server-brave-search` | Web 搜索 |

---

## 配置与 Spring 注入

```
src/test/resources/
├── mcp.config.json          ← McpManager 读取的 HTTP 工具
└── mcp.server.config.json   ← McpClient 读取的 NPX 服务器
```

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

> 基础示例与直连代码：`Article08Mcp.java`  
> Function Calling ReAct：`Article11McpReactAgent.java`  
> `McpAgentExecutor` 单源/混合：`Article12McpManagerAgent.java`、`Article13McpClientAgent.java`、`Article14McpMixedAgent.java`  
> 客服双 Agent（ReAct + MCP）：`Article16CustomerService.java`
