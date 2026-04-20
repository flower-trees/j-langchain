# McpAgentExecutor Mixed Mounting: HTTP Tools and NPX Servers in the Same Agent

> **Tags**: `Java` `MCP` `Agent` `j-langchain` `McpAgentExecutor` `mixed tools` `Function Calling`  
> **Prerequisite**: [McpAgentExecutor: Let Models Call HTTP Tools in a Few Lines](12-mcp-manager-agent.md) → [McpAgentExecutor + McpClient: Let Agents Operate Filesystems and Databases](13-mcp-client-agent.md)  
> **Audience**: Java developers who have separately integrated HTTP tools and NPX servers and want to use both in the same Agent

---

## 1. From Single Source to Mixed Sources

The previous two articles introduced two tool sources:

- **Article 12**: `McpManager` + `mcp.config.json` → HTTP API tools (query IP, weather, internal APIs)
- **Article 13**: `McpClient` + `mcp.server.config.json` → NPX MCP servers (filesystem, database, memory storage)

In real business, a single task often spans both types of tools. The typical scenario is: call an HTTP API to fetch data, then write the result to a file or store it in a database. If you maintain two separate Agents and manually stitch results together for this kind of task, the code is messy and introduces an unnecessary coordination layer.

`McpAgentExecutor` supports chaining multiple `.tools(...)` calls, allowing you to mount tools from multiple sources on the same Agent. The model can freely choose between HTTP tools and NPX tools during reasoning — the developer doesn't need to care about the tool source.

---

## 2. Core Usage: Chain-register Tools

```java
McpAgentExecutor agent = McpAgentExecutor.builder(chainActor)
    .llm(ChatAliyun.builder().model("qwen3.6-plus").temperature(0f).build())
    .tools(mcpManager, "default")         // Layer 1: HTTP tools (get_export_ip etc.)
    .tools(mcpClient, "filesystem")       // Layer 2: NPX filesystem tools
    .systemPrompt("""
        你是一个智能助手，可以调用工具获取网络信息和操作文件系统。
        当你完成用户的所有要求后，直接给出最终答案，不要再调用任何工具。
        """)
    .maxIterations(8)
    .onToolCall(tc -> System.out.println(">> Tool: " + tc))
    .onObservation(obs -> System.out.println(">> Result: " + obs))
    .build();
```

The tool lists from both `.tools(...)` calls are merged internally by the framework, converted uniformly to a Function Calling Schema and handed to the model. Registration order doesn't affect functionality, but one thing to note: **if two sources have a tool with the same name, the later-registered one overwrites the earlier one**. It's recommended to use different naming conventions in `mcp.config.json` and server names, organized by business domain, to avoid conflicts.

---

## 3. Full Example: Query Public IP and Write to File

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

## 4. Complete Reasoning Trace

The task spans three tools — one from HTTP, two from the NPX server — and the model completes all calls autonomously:

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

The model has no concept of "this tool comes from HTTP, that tool comes from NPX" — it only sees one unified tool list and picks what it needs based on the task. This is the core value of mixed mounting: **the tool source is transparent to the model, and the developer doesn't need to do any extra handling either**.

---

## 5. Is a Bigger Tool List Always Better?

Mounting all tools onto one Agent sounds convenient, but in practice the size of the tool list has a noticeable impact on effectiveness.

**More tools means a higher probability the model picks the wrong one.** Function Calling is essentially asking the model to select the best function from a set of candidates — the larger the candidate set, the more interference. In practice, when the tool count exceeds 15~20, the model's selection accuracy drops noticeably.

**The recommended approach is to split tools into groups by task, rather than stuffing everything into one Agent:**

```json
// mcp.config.json
{
  "network": ["get_export_ip", "get_ip_location", "get_weather_open_meteo"],
  "crm":     ["query_customer", "update_order", "send_notification"],
  "ops":     ["check_server_status", "get_disk_usage", "restart_service"]
}
```

Different Agents load only the tool groups they need:

```java
// Network diagnosis Agent: only network group + filesystem
.tools(mcpManager, "network")
.tools(mcpClient, "filesystem")

// Customer service Agent: only CRM group
.tools(mcpManager, "crm")

// Operations Agent: only ops group + memory
.tools(mcpManager, "ops")
.tools(mcpClient, "memory")
```

This keeps each Agent's tool list at 5~10 tools, improving selection accuracy and making System Prompts easier to write concisely.

---

## 6. How the System Prompt Affects Mixed-tool Agents

After mounting multi-source tools, the quality of the System Prompt has an even greater impact on Agent performance. The difference between these two styles is significant:

**Vague style (prone to problems):**
```
你是一个智能助手，可以调用工具回答用户问题。
```

The model may call tools back and forth, or continue calling unnecessary tools after the task is done.

**Specific style (recommended):**
```
你是一个网络诊断助手。
任务流程：先用 HTTP 工具获取网络信息，再用文件系统工具保存结果，保存完成后直接给出结论，停止调用工具。
每个工具最多调用一次，不要重复查询相同信息。
```

Explicitly describing the task flow significantly reduces unnecessary tool calls, and also makes `maxIterations` settings more precise.

---

## 7. How to Set `maxIterations`

In mixed mounting scenarios, a single task may span multiple tools, so `maxIterations` needs more careful estimation.

Basic principle: **estimate the worst-case number of tool calls and add 2~3 as a buffer**.

Using this example:
- `get_export_ip` (1 call)
- `write_file` (1 call)
- `read_file` (1 call)
- Worst-case total: 3 Actions

Setting `maxIterations(8)` is sufficient. For more complex tasks (querying multiple data sources, writing multiple files), increase accordingly.

The side effect of setting it too large: when the model gets stuck in repeated calls, it takes more rounds to trigger termination, wasting tokens. **Adding "stop calling tools when done" to the System Prompt effectively reduces this situation.**

---

## 8. Summary

| Tool source | Corresponding config | How to register |
|---|---|---|
| HTTP API | `mcp.config.json` | `.tools(mcpManager, "group-name")` |
| NPX MCP server | `mcp.server.config.json` | `.tools(mcpClient, "server-alias")` |
| Both mixed | Each config file handles its own | Chain two `.tools(...)` calls |

Mixed mounting introduces no new concepts — it's just chaining the two `.tools(...)` calls from the previous two articles. The framework merges tool lists and manages the reasoning loop; the developer only needs to focus on **whether tool grouping makes sense** and **whether the System Prompt is clear**.

The finer the tool grouping, the more focused each Agent's responsibility, and the higher the model's selection accuracy.

---

> 📎 Resources
> - Full example: [Article14McpMixedAgent.java](../../../src/test/java/org/salt/jlangchain/demo/article/Article14McpMixedAgent.java), method `mcpMixedAgent()`
> - Tool config: [mcp.config.json](../../../src/test/resources/mcp.config.json) and [mcp.server.config.json](../../../src/test/resources/mcp.server.config.json)
> - j-langchain GitHub: https://github.com/flower-trees/j-langchain
> - j-langchain Gitee mirror: https://gitee.com/flower-trees-z/j-langchain
> - Runtime requirements: Aliyun API Key required, example model `qwen3.6-plus`; Node.js must be installed locally to run npx
