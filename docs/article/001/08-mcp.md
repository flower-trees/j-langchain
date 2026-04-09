# Java Agent 集成 MCP 工具协议：让 AI 真正驱动企业系统

> **标签**：`Java` `MCP` `Agent` `j-langchain` `LLM` `工具调用` `Function Calling`  
> **适合人群**：希望把企业 API、数据库、文件系统等能力统一接入 Java Agent 的开发者

---

## 一、问题从这里开始

做 AI 应用到一定阶段，几乎每个团队都会遇到同一个瓶颈：

> 模型本身很聪明，但它什么都摸不到。查个库存要封装一个 Tool，查个天气要封装一个 Tool，接一个新系统又要重写一套……工具越堆越多，每个 Agent 都在重复造轮子。

这个问题的根源在于**工具层没有统一标准**。Anthropic 在 2024 年推出的 **MCP（Model Context Protocol）** 正是为了解决这个问题：

- 用统一的协议描述工具能力
- 官方 + 社区生态提供大量即插即用的服务器
- 工具声明一次，所有 Agent 都能复用

本文介绍如何在 j-langchain 中集成 MCP，涵盖两种接入方式，并给出从工具注册到 LLM 自动调用的完整链路。

---

## 二、两种接入方式，覆盖绝大多数场景

j-langchain 提供两种 MCP 接入方式，分别对应不同的工具来源：

| 方式 | 核心类 | 适用场景 |
|---|---|---|
| HTTP API → MCP 工具 | `McpManager` | 企业内部 REST API、第三方 HTTP 服务 |
| NPX MCP 服务器 | `McpClient` | 文件系统、数据库、GitHub、浏览器自动化等标准能力 |

两者可以混合使用，`McpManager` 管你自己的 API，`McpClient` 对接社区生态。

---

## 三、方式一：用 McpManager 把 HTTP API 变成 MCP 工具

### 工作原理

`McpManager` 读取一份 `mcp.config.json` 配置文件，将里面描述的每个 HTTP 接口自动注册为 MCP 工具，模型就能通过标准的 Function Calling 接口调用它们。

整体流程如下：

```
mcp.config.json（工具描述）
       ↓
McpManager（加载 & 管理）
       ↓
manifest()          → 工具清单（注入 Prompt，告诉模型有哪些工具）
manifestForInput()  → JSON Schema（交给模型，支持 Function Calling）
run() / runForInput() → 真正执行 HTTP 请求
```

### 配置文件示例

`mcp.config.json` 就是一个工具菜单，每个条目描述一个 HTTP 接口：

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

> `default` 是分组名，可以按业务模块拆分成多个分组，Agent 按需加载。

### 代码示例

注册 Bean 只需两行：

```java
@Bean
public McpManager mcpManager() throws Exception {
    return new McpManager("mcp.config.json");
}
```

查看工具清单：

```java
@Test
public void mcpManagerManifest() {
    // 文字描述版（可注入 Prompt，让模型知道有哪些工具）
    System.out.println(JsonUtil.toJson(mcpManager.manifest()));

    // JSON Schema 版（交给支持 Function Calling 的模型直接使用）
    System.out.println(JsonUtil.toJson(mcpManager.manifestForInput()));
}
```

直接调用工具，验证接口是否正确：

```java
@Test
public void mcpManagerRun() throws Exception {
    // 直接调用，返回原始结果
    Object result = mcpManager.run("default", "get_export_ip", Map.of());
    System.out.println("接口返回：" + JsonUtil.toJson(result));

    // 返回 LLM 格式化输入（方便注入 Observation）
    Object inputResult = mcpManager.runForInput("default", "get_export_ip", Map.of());
    System.out.println("LLM 格式：" + JsonUtil.toJson(inputResult));
}
```

---

## 四、方式二：用 McpClient 接入 NPX MCP 服务器

### 为什么需要这种方式

并不是所有能力都能封装成 HTTP API。文件读写、SQL 查询、Git 操作、浏览器自动化……这些能力如果都自己封装，工作量巨大。

MCP 生态已经提供了一批**官方标准服务器**，每个服务器对应一类能力，通过 `npx` 一条命令启动，不需要自己写任何服务端代码。

### 常用 MCP 服务器速查

| 服务器包名 | 能力 | 典型用途 |
|---|---|---|
| `@modelcontextprotocol/server-filesystem` | 本地/挂载目录读写 | Agent 读取配置、写入报告 |
| `@modelcontextprotocol/server-memory` | KV 键值存储 | Agent 跨轮次记忆 |
| `@modelcontextprotocol/server-postgres` | PostgreSQL 查询 | 自动化数据报表、智能 BI |
| `@modelcontextprotocol/server-github` | Issue/PR/仓库操作 | 代码 Review Agent、自动归档 |
| `@modelcontextprotocol/server-puppeteer` | 浏览器自动化 | 网页截图、表单填写 |
| `@modelcontextprotocol/server-brave-search` | 网络搜索 | 实时信息检索 |

### 配置文件示例

`mcp.server.config.json` 描述需要启动哪些服务器、传哪些参数：

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

### 代码示例

注册 Bean：

```java
@Bean
public McpClient mcpClient() {
    return new McpClient("mcp.server.config.json");
}
```

一次列出所有服务器的工具清单：

```java
@Test
public void mcpClientListTools() {
    // 列出所有已配置服务器及其工具
    System.out.println(JsonUtil.toJson(mcpClient.listAllTools()));
}
```

需要精细控制某个服务器时，直接使用 `McpServerConnection`：

```java
@Test
public void mcpMemoryServerConnect() throws Exception {
    ServerConfig config = new ServerConfig();
    config.command = "npx";
    config.args = List.of("-y", "@modelcontextprotocol/server-memory");
    config.env = new HashMap<>();

    McpServerConnection connection = new McpServerConnection("memory-server", config);
    connection.connect();

    System.out.println("连接状态：" + connection.isConnected());
    System.out.println("可用工具：" + JsonUtil.toJson(connection.listTools()));

    // 调用具体工具
    Object result = connection.callTool("search_nodes", new HashMap<>());
    System.out.println("查询结果：" + JsonUtil.toJson(result));
}
```

接入 PostgreSQL 同理，换个服务器包名就行——**Agent 从此不用写 JDBC**：

```java
@Test
public void mcpPostgresConnect() throws Exception {
    ServerConfig config = new ServerConfig();
    config.command = "npx";
    config.args = List.of(
        "-y",
        "@modelcontextprotocol/server-postgres",
        "postgresql://user:password@localhost:5432/mydb"  // 替换为实际连接串
    );
    config.env = new HashMap<>();

    McpServerConnection connection = new McpServerConnection("postgres-server", config);
    connection.connect();

    System.out.println("可用工具：" + JsonUtil.toJson(connection.listTools()));
    // 有了 MCP，Agent 直接执行 SQL，无需任何 JDBC 代码
    // connection.callTool("query", Map.of("sql", "SELECT * FROM orders LIMIT 10"));
}
```

---

## 五、把工具交给模型：从工具注册到自动调用

前面两步完成了"**工具注册**"，这一步把工具真正交给 LLM，让模型自己决定什么时候调用、调用哪个。

### 完整链路

```
用户提问
   ↓
mcpManager.manifestForInput()  → 获取工具的 JSON Schema
   ↓
注入 ChatAliyun（支持 Function Calling 的模型）
   ↓
模型推理，返回 ToolCall（工具名 + 参数）
   ↓
mcpManager.run(...)            → 执行真实 HTTP 请求
   ↓
把结果拼回对话，模型生成最终回答
```

### 代码实现

```java
@Test
public void mcpLlmDemo() {

    // 1. 获取 MCP 工具的 JSON Schema（模型需要这个格式才能自动调用）
    List<AiChatInput.Tool> tools = mcpManager.manifestForInput().get("default");

    // 2. 构建 Prompt 模板
    BaseRunnable<ChatPromptValue, ?> prompt = ChatPromptTemplate.fromMessages(
        List.of(
            BaseMessage.fromMessage(
                MessageType.SYSTEM.getCode(),
                "你是一个 AI 助手，可以调用 tools 中的工具回答用户问题。"
            ),
            BaseMessage.fromMessage(
                MessageType.HUMAN.getCode(),
                "用户问题：${input}"
            )
        )
    );

    // 3. 配置 LLM，注入工具清单
    ChatAliyun llm = ChatAliyun.builder()
        .model("qwen3.6-plus")
        .temperature(0f)
        .tools(tools)   // 把 MCP 工具直接注册给模型
        .build();

    // 4. 构建链
    FlowInstance chain = chainActor.builder()
        .next(prompt)
        .next(llm)
        .build();

    // 5. 执行，模型会返回 ToolCall（它决定调用哪个工具、传什么参数）
    ToolMessage result = chainActor.invoke(chain, Map.of("input", "告诉我当前的公网 IP"));
    System.out.println("模型选择调用：" + result.getToolCalls());

    // 6. 拦截 ToolCall，用 McpManager 真正执行
    // mcpManager.run("default", toolCall.getName(), toolCall.getArguments());
}
```

运行后，模型会返回类似这样的 ToolCall：

```json
[{
  "name": "get_export_ip",
  "arguments": {}
}]
```

拿到这个 ToolCall，调用 `mcpManager.run(...)` 就能完成真实请求，再把结果拼回对话，模型就能给出最终回答。

> 💡 **关键优势**：工具声明一次写在 `mcp.config.json`，无论有多少个 Agent、多少条链路，全部直接复用这份配置，不需要任何重复封装。

---

## 六、项目结构参考

```
src/test/resources/
├── mcp.config.json           # HTTP API 工具声明
└── mcp.server.config.json    # NPX MCP 服务器声明

src/test/java/
└── [Article08Mcp.java](../../../src/test/java/org/salt/jlangchain/demo/article/Article08Mcp.java)         # 本文完整示例代码
```

Spring Bean 注册（测试配置或应用配置均可）：

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

## 七、总结

| 场景 | 推荐方式 | 核心类 |
|---|---|---|
| 接入企业内部 REST API | HTTP → MCP | `McpManager` + `mcp.config.json` |
| 接入文件系统、数据库、GitHub 等 | NPX MCP 服务器 | `McpClient` + `mcp.server.config.json` |
| 让模型自动决策调用哪个工具 | Function Calling | `manifestForInput()` + 支持 tools 的 LLM |
| 精细控制单个服务器 | 直连服务器 | `McpServerConnection` |

MCP 带来的核心价值只有一句话：**工具声明一次，所有 Agent 复用，生态内的能力直接拿来用，不用重复造轮子。**

---

> 📎 相关资源
> - j-langchain GitHub：https://github.com/flower-trees/j-langchain
> - j-langchain Gitee 镜像：https://gitee.com/flower-trees-z/j-langchain
> - MCP 官方规范：https://modelcontextprotocol.io
> - 完整示例代码：[Article08Mcp.java](../../../src/test/java/org/salt/jlangchain/demo/article/Article08Mcp.java)