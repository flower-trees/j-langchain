# 在 Java AI 应用中集成 MCP 工具协议

> **适合人群**：需要让 AI Agent 调用外部工具（数据库、文件系统、API）的 Java 开发者  
> **MCP 版本**：Model Context Protocol（2024 年 Anthropic 发布）

---

## 什么是 MCP？

**MCP（Model Context Protocol）** 是 Anthropic 于 2024 年发布的开放协议，目标是标准化 AI 模型与外部工具之间的通信方式。

就像 HTTP 统一了 Web 客户端和服务器的通信，MCP 统一了 AI 模型调用各种工具的方式。

**没有 MCP 的时代**：
- 接入每个工具都要写定制代码
- 工具的描述格式各不相同
- 不同 AI 平台不通用

**有了 MCP**：
- 工具实现一次，所有 MCP 兼容的 AI 系统都能用
- 标准化的工具描述、调用、返回格式
- 生态快速爆发：文件系统、数据库、浏览器、代码执行...

---

## MCP 在 j-langchain 中的架构

j-langchain 提供两种 MCP 集成方式：

```
方式1：HTTP API 工具（McpManager）
  mcp.config.json → McpManager → HTTP API 调用

方式2：NPX MCP 服务器（McpClient + McpServerConnection）
  mcp.server.config.json → McpClient → NPX 进程 → MCP 工具
```

---

## 方式1：HTTP API 工具（McpManager）

适合将自己的 HTTP API 包装成 MCP 工具，供 Agent 调用。

### 配置文件 `mcp.config.json`

```json
{
  "default": [
    {
      "name": "get_export_ip",
      "description": "Get my network export IP",
      "url": "http://ipinfo.io/ip",
      "method": "GET",
      "params": {}
    },
    {
      "name": "query_weather",
      "description": "查询城市天气",
      "url": "https://api.weather.com/v1/forecast",
      "method": "GET",
      "params": {
        "city": {
          "description": "城市名称",
          "type": "string"
        },
        "required": ["city"]
      }
    }
  ]
}
```

### 查看工具清单

```java
@Test
public void mcpManagerManifest() {
    // 列出所有工具（供 LLM 理解）
    System.out.println(JsonUtil.toJson(mcpManager.manifest()));

    // 列出工具的输入格式（供 Agent 格式化调用参数）
    System.out.println(JsonUtil.toJson(mcpManager.manifestForInput()));
}
```

`manifest()` 的输出用于注入 Agent Prompt，告诉 LLM 有哪些工具可用：

```json
[
  {
    "name": "get_export_ip",
    "description": "Get my network export IP",
    "params": {}
  }
]
```

### 调用工具

```java
@Test
public void mcpManagerRun() throws Exception {
    // 直接调用工具
    Object result = mcpManager.run("default", "get_export_ip", Map.of());
    System.out.println(result);  // "203.0.113.45"

    // 返回 LLM 格式化输入（适合注入 Agent Prompt）
    Object inputResult = mcpManager.runForInput("default", "get_export_ip", Map.of());
    System.out.println(inputResult);
}
```

---

## 方式2：NPX MCP 服务器（McpClient）

MCP 生态中有大量开源服务器，可以通过 NPX 一键启动，无需自己实现。

### 配置文件 `mcp.server.config.json`

```json
{
  "mcpServers": {
    "filesystem": {
      "command": "npx",
      "args": ["-y", "@modelcontextprotocol/server-filesystem", "/tmp"],
      "env": {"NODE_ENV": "production"}
    },
    "memory": {
      "command": "npx",
      "args": ["-y", "@modelcontextprotocol/server-memory"],
      "env": {}
    }
  }
}
```

### 列出所有工具

```java
@Test
public void mcpClientListTools() {
    // McpClient 自动连接所有配置的 NPX 服务器
    System.out.println(JsonUtil.toJson(mcpClient.listAllTools()));
}
```

### 调用 NPX MCP 工具

```java
// 调用 cognee 知识图谱搜索
mcpClient.callTool("cognee", "search", Map.of(
    "search_query", "what is cognee?",
    "search_type", "GRAPH_COMPLETION"
));

// 调用文件系统工具（列出目录）
mcpClient.callTool("filesystem", "list_directory", Map.of("path", "/tmp"));
```

---

## 方式3：直接连接 MCP 服务器

需要精细控制连接生命周期时，直接使用 `McpServerConnection`：

### Memory 服务器（Agent 记忆）

```java
@Test
public void mcpMemoryServerConnect() throws Exception {
    ServerConfig config = new ServerConfig();
    config.command = "npx";
    config.args = List.of("-y", "@modelcontextprotocol/server-memory");
    config.env = new HashMap<>();

    McpServerConnection connection = new McpServerConnection("memory-server", config);
    connection.connect();

    // 查看可用工具
    System.out.println(JsonUtil.toJson(connection.listTools()));

    // 调用工具
    connection.callTool("create_entities", Map.of(
        "entities", List.of(Map.of(
            "name", "用户偏好",
            "entityType", "preference",
            "observations", List.of("喜欢 Java", "不喜欢 XML")
        ))
    ));

    connection.callTool("search_nodes", Map.of("query", "用户喜欢什么编程语言？"));
}
```

### PostgreSQL 服务器（让 Agent 查数据库）

```java
@Test
public void mcpPostgresConnect() throws Exception {
    ServerConfig config = new ServerConfig();
    config.command = "npx";
    config.args = List.of(
        "-y",
        "@modelcontextprotocol/server-postgres",
        "postgresql://user:password@localhost:5432/mydb"
    );

    McpServerConnection connection = new McpServerConnection("postgres-server", config);
    connection.connect();

    // Agent 现在可以直接执行 SQL 查询，无需你写 JDBC 代码
    Object result = connection.callTool("query", Map.of(
        "sql", "SELECT name, email FROM users LIMIT 5"
    ));
    System.out.println(result);
}
```

---

## MCP + Agent：`McpAgentExecutor`（Function Calling）

若希望模型以 **结构化 Function Calling** 自动多轮选工具（而非手写 ReAct 文本解析），可使用 **`McpAgentExecutor`**：

- **仅 HTTP 工具（McpManager）** → [文章 11：McpAgentExecutor + McpManager](11-mcp-manager-agent.md)（`Article11McpManagerAgent`）
- **仅 NPX 服务器（McpClient）** → [文章 12：McpAgentExecutor + McpClient](12-mcp-client-agent.md)（`Article12McpClientAgent`）
- **HTTP + NPX 同时挂载** → [文章 13：混合模式](13-mcp-mixed-agent.md)（`Article13McpMixedAgent`）

若需 **ReAct 文本协议** 与 MCP 的组合编排，可继续使用 [文章 4](04-react-agent.md)、[文章 9](09-agent-executor.md) 的手动链或 `AgentExecutor`；HTTP MCP 工具 + 模型原生 ToolCall 的 ReAct 版参见 [文章 10：MCP Function-Calling ReAct](10-mcp-react-agent.md)。`Article08Mcp` 中的 **`dualAgentChain`** 则演示了 **AgentExecutor + McpAgentExecutor** 双 Agent 串联。

---

## 常用 MCP 服务器一览

| 服务器 | NPX 命令 | 功能 |
|--------|----------|------|
| 文件系统 | `@modelcontextprotocol/server-filesystem` | 读写本地文件 |
| 内存存储 | `@modelcontextprotocol/server-memory` | 持久化 KV 存储（Agent 记忆） |
| PostgreSQL | `@modelcontextprotocol/server-postgres` | 查询 PostgreSQL 数据库 |
| GitHub | `@modelcontextprotocol/server-github` | 操作 GitHub 仓库 |
| 浏览器 | `@modelcontextprotocol/server-puppeteer` | 控制浏览器、截图、爬取 |
| Brave搜索 | `@modelcontextprotocol/server-brave-search` | 网络搜索 |

---

## 配置总结

```
src/test/resources/
├── mcp.config.json          ← HTTP API 工具配置（McpManager 读取）
└── mcp.server.config.json   ← NPX MCP 服务器配置（McpClient 读取）
```

**Spring Bean 配置**（参考 `JLangchainConfigTest`）：

```java
@Bean
public McpManager mcpManager() {
    return new McpManager("classpath:mcp.config.json");
}

@Bean
public McpClient mcpClient() {
    return new McpClient("classpath:mcp.server.config.json");
}
```

---

> 基础与直连示例：`src/test/java/org/salt/jlangchain/demo/article/Article08Mcp.java`  
> MCP + ReAct 手写链：`Article10McpReactAgent`  
> `McpAgentExecutor` 单源/混合：`Article11McpManagerAgent`、`Article12McpClientAgent`、`Article13McpMixedAgent`
