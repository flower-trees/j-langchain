# McpAgentExecutor 混合挂载：HTTP 工具与 NPX 服务器同时接入同一 Agent

> **标签**：`Java` `MCP` `Agent` `j-langchain` `McpAgentExecutor` `混合工具` `Function Calling`  
> **前置阅读**：[McpAgentExecutor：HTTP 工具一键托管]() → [McpAgentExecutor + McpClient：让 Agent 直接操作文件系统和数据库]()  
> **适合人群**：已分别接入 HTTP 工具和 NPX 服务器，希望在同一个 Agent 中串联使用的 Java 开发者

---

## 一、从单一来源到混合来源

前两篇文章分别介绍了两种工具来源：

- **文章 12**：`McpManager` + `mcp.config.json` → HTTP API 工具（查 IP、查天气、调内部接口）
- **文章 13**：`McpClient` + `mcp.server.config.json` → NPX MCP 服务器（文件系统、数据库、记忆存储）

但真实业务中，一个任务往往横跨两类工具。典型的场景是：先调用 HTTP API 获取数据，再把结果写入文件或存入数据库。如果为这类任务专门维护两个 Agent、手动拼接结果，不仅代码繁琐，还引入了不必要的协调层。

`McpAgentExecutor` 支持链式 `.tools(...)` 调用，可以在同一个 Agent 上同时挂载多个来源的工具。模型在推理时能自由选择 HTTP 工具或 NPX 工具，开发者不需要关心工具的来源。

---

## 二、核心用法：链式注册工具

```java
McpAgentExecutor agent = McpAgentExecutor.builder(chainActor)
    .llm(ChatAliyun.builder().model("qwen3.6-plus").temperature(0f).build())
    .tools(mcpManager, "default")         // 第一层：HTTP 工具（get_export_ip 等）
    .tools(mcpClient, "filesystem")       // 第二层：NPX filesystem 工具
    .systemPrompt("""
        你是一个智能助手，可以调用工具获取网络信息和操作文件系统。
        当你完成用户的所有要求后，直接给出最终答案，不要再调用任何工具。
        """)
    .maxIterations(8)
    .onToolCall(tc -> System.out.println(">> Tool: " + tc))
    .onObservation(obs -> System.out.println(">> Result: " + obs))
    .build();
```

两次 `.tools(...)` 调用的工具列表会在框架内部合并，统一转成 Function Calling Schema 交给模型。注册顺序不影响功能，但有一点需要注意：**如果两个来源中存在同名工具，后注册的会覆盖前一个**，建议在 `mcp.config.json` 和服务器命名时按业务域区分，避免冲突。

---

## 三、完整示例：查询公网 IP 并写入文件

```java
@Test
public void mcpMixedAgent() {

    McpAgentExecutor agent = McpAgentExecutor.builder(chainActor)
        .llm(ChatAliyun.builder().model("qwen3.6-plus").temperature(0f).build())
        .tools(mcpManager, "default")
        .tools(mcpClient, "filesystem")
        .systemPrompt("""
            你是一个智能助手，可以调用工具获取网络信息和操作文件系统。
            当你完成用户的所有要求后，直接给出最终答案，不要再调用任何工具。
            """)
        .maxIterations(8)
        .onToolCall(tc -> System.out.println(">> Tool: " + tc))
        .onObservation(obs -> System.out.println(">> Result: " + obs))
        .build();

    ChatGeneration result = agent.invoke(
        "帮我查一下公网 IP，然后把 IP 地址写入 /tmp/my_ip.txt 文件，读取文件内容确认写入成功"
    );

    System.out.println("\n=== 最终答案 ===");
    System.out.println(result.getText());
}
```

---

## 四、完整推理轨迹

任务横跨三个工具——一个来自 HTTP，两个来自 NPX 服务器——模型自主完成全部调用：

```
>> Tool: get_export_ip -> {}
>> Result: 123.117.177.40

>> Tool: write_file -> {"path": "/tmp/my_ip.txt", "content": "123.117.177.40"}
>> Result: 写入成功

>> Tool: read_file -> {"path": "/tmp/my_ip.txt"}
>> Result: 123.117.177.40

=== 最终答案 ===
公网 IP 已成功写入 /tmp/my_ip.txt，读取确认内容为 123.117.177.40。
```

模型没有任何"这个工具来自 HTTP、那个工具来自 NPX"的概念，它只看到一份统一的工具列表，根据任务需要自行选择。这是混合挂载最核心的价值：**工具的来源对模型透明，对开发者也不需要额外处理**。

---

## 五、工具列表越大越好吗？

把所有工具都挂到一个 Agent 上听起来很方便，但实际使用中工具列表的大小对效果有明显影响。

**工具越多，模型选错的概率越高。** Function Calling 本质上是让模型从一组候选函数中选出最合适的，候选集越大，干扰越多。实际测试中，工具数量超过 15~20 个时，模型的选择准确率会有明显下降。

**推荐做法是按任务拆分工具组，而不是把所有工具塞进一个 Agent：**

```json
// mcp.config.json
{
  "network": ["get_export_ip", "get_ip_location", "get_weather_open_meteo"],
  "crm":     ["query_customer", "update_order", "send_notification"],
  "ops":     ["check_server_status", "get_disk_usage", "restart_service"]
}
```

不同 Agent 只加载自己需要的工具组：

```java
// 网络诊断 Agent：只挂 network 组 + filesystem
.tools(mcpManager, "network")
.tools(mcpClient, "filesystem")

// 客服 Agent：只挂 crm 组
.tools(mcpManager, "crm")

// 运维 Agent：只挂 ops 组 + memory
.tools(mcpManager, "ops")
.tools(mcpClient, "memory")
```

这样每个 Agent 的工具列表保持在 5~10 个，选择准确率更高，System Prompt 也更容易写得具体。

---

## 六、System Prompt 写法对混合工具的影响

挂载多来源工具后，System Prompt 的质量对 Agent 的表现影响更大。以下两种写法的效果差距明显：

**模糊写法（容易出问题）：**
```
你是一个智能助手，可以调用工具回答用户问题。
```

模型可能会在工具之间来回调用，或者在任务完成后继续调用不必要的工具。

**具体写法（推荐）：**
```
你是一个网络诊断助手。
任务流程：先用 HTTP 工具获取网络信息，再用文件系统工具保存结果，保存完成后直接给出结论，停止调用工具。
每个工具最多调用一次，不要重复查询相同信息。
```

明确的任务流程描述能显著减少模型的无效调用，也能让 `maxIterations` 的设置更加精准。

---

## 七、maxIterations 怎么设置

混合挂载场景下，一次任务可能横跨多个工具，`maxIterations` 的设置需要更仔细估算。

基本原则：**预估最坏情况下的工具调用次数，再加 2~3 作为 buffer**。

以本例为例：
- `get_export_ip`（1次）
- `write_file`（1次）
- `read_file`（1次）
- 最坏情况合计 3 次 Action

设置 `maxIterations(8)` 是足够的。如果任务更复杂（如查多个数据源、写多个文件），相应调大即可。

设得过大的副作用是：当模型陷入重复调用时，需要消耗更多轮次才能触发终止，浪费 Token。**System Prompt 里加一句"完成后停止调用工具"能有效减少这种情况。**

---

## 八、总结

| 工具来源 | 对应配置 | 注册方式 |
|---|---|---|
| HTTP API | `mcp.config.json` | `.tools(mcpManager, "分组名")` |
| NPX MCP 服务器 | `mcp.server.config.json` | `.tools(mcpClient, "服务器别名")` |
| 两者混合 | 两个配置文件各配各的 | 链式调用两次 `.tools(...)` |

混合挂载没有引入任何新概念，只是把前两篇文章的两行 `.tools(...)` 拼在一起。框架负责合并工具列表、管理推理循环，开发者只需关注**工具分组是否合理**和**System Prompt 是否清晰**这两件事。

工具分组拆得越细，每个 Agent 的职责越单一，模型的选择准确率也越高。

---

> 📎 相关资源
> - 完整示例：[Article14McpMixedAgent.java](../../../src/test/java/org/salt/jlangchain/demo/article/Article14McpMixedAgent.java)，对应方法 `mcpMixedAgent()`
> - 工具配置：[mcp.config.json](../../../src/test/resources/mcp.config.json) 和 [mcp.server.config.json](../../../src/test/resources/mcp.server.config.json)
> - j-langchain GitHub：https://github.com/flower-trees/j-langchain
> - j-langchain Gitee 镜像：https://gitee.com/flower-trees-z/j-langchain
> - 运行环境：需配置阿里云 API Key，示例模型 `qwen3.6-plus`；需本地安装 Node.js 以运行 npx