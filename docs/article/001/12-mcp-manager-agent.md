# McpAgentExecutor：用几行代码让模型自主调用 HTTP 工具

> **标签**：`Java` `MCP` `Function Calling` `Agent` `j-langchain` `McpAgentExecutor` `工具调用`  
> **前置阅读**：[Java Agent 集成 MCP 工具协议](08-mcp.md) → [MCP + Function Calling：让模型自主驱动工具链](11-mcp-react-agent.md)  
> **适合人群**：已配置好 MCP 工具，不想手写 Function Calling 循环的 Java 开发者

---

## 一、上篇留下的样板代码

上篇文章实现了一个完整的"MCP + Function Calling"多步推理链路，核心部分大致是这样的：

```java
// 循环条件：有 ToolCall 就继续
Function<Integer, Boolean> shouldContinue = round -> { ... };

// 工具执行处理器：解析 ToolCall → 执行 MCP → 写回 Observation
TranslateHandler<Object, AIMessage> executeMcpTool = new TranslateHandler<>(msg -> {
    // 把 ToolCall 记入历史
    // 解析工具名和参数
    // 执行 mcpManager.runForInput(...)
    // 把结果以 ToolMessage 写回 Prompt
    ...
});

// 组装链路
FlowInstance chain = chainActor.builder()
    .next(prompt)
    .loop(shouldContinue, llm, chainActor.builder()
        .next(Info.c(needsToolExecution, executeMcpTool), Info.c(...))
        .build())
    .next(new StrOutputParser())
    .build();
```

这段代码的结构是正确的，理解它对掌握底层原理很有价值。但如果项目中有多个 Agent 都要接 MCP 工具，每次都写这些样板代码就显得多余了。

`McpAgentExecutor` 把这些逻辑封装进去，对外只暴露真正需要配置的部分：**用哪个模型、加载哪组工具、系统提示写什么**。

---

## 二、最简示例：五行代码完成多步工具调用

```java
@Test
public void mcpManagerAgent() {

    McpAgentExecutor agent = McpAgentExecutor.builder(chainActor)
        .llm(ChatAliyun.builder().model("qwen3.6-plus").temperature(0f).build())
        .tools(mcpManager, "default")       // 加载 mcp.config.json 的 default 工具组
        .systemPrompt("你是一个智能助手，可以调用工具获取信息后回答用户问题。")
        .maxIterations(5)
        .onToolCall(tc -> System.out.println(">> Tool call: " + tc))
        .onObservation(obs -> System.out.println(">> Observation: " + obs))
        .build();

    ChatGeneration result = agent.invoke("帮我查一下我的公网 IP 是什么？");

    System.out.println("\n=== 最终答案 ===");
    System.out.println(result.getText());
}
```

`.tools(mcpManager, "default")` 这一行做了上篇需要手写的所有准备工作：读取 `mcp.config.json` 中 `default` 分组的工具列表，转成 Function Calling 所需的 JSON Schema，注册给模型。

---

## 三、执行效果

```
>> Tool call: ToolCall(id=..., function=get_export_ip, arguments={})
>> Observation: 123.117.177.40

=== 最终答案 ===
你的公网 IP 为 123.117.177.40。
```

如果换一个需要多步推理的问题，例如"查我的公网 IP 并告诉我当前天气"，模型会在同一次 `invoke` 中自动完成多轮 ToolCall，整个过程对调用方完全透明：

```
>> Tool call: get_export_ip → 123.117.177.40
>> Tool call: get_ip_location → {"city": "Dongcheng Qu", "latitude": 39.91, ...}
>> Tool call: get_weather_open_meteo → {"temperature": 18.3, "weathercode": 1}

=== 最终答案 ===
你位于北京市东城区，当前气温 18.3°C，天气晴朗。
```

工具数量、调用顺序、参数拼装全部由模型决定，开发者只需保证所需工具在 `mcp.config.json` 的同一分组中。

---

## 四、与上篇手写版本的对比

| 维度 | 手写 Function Calling 循环（文章 11） | McpAgentExecutor（本篇） |
|---|---|---|
| 代码量 | ~60 行（循环、处理器、链路组装） | ~10 行 |
| 循环逻辑 | 自己维护 `shouldContinue` 和处理器 | 框架内置 |
| ToolCall 解析 | 手动解析 JSON、写回 ToolMessage | 框架自动处理 |
| 调试能力 | 完全自定义日志位置 | `onToolCall` / `onObservation` 回调 |
| 适合场景 | 需要在循环内插入自定义逻辑 | 标准工具调用，无需定制 |

两种方式并不互斥。`McpAgentExecutor` 处理不了的场景（比如工具调用前需要做权限校验，或者需要根据中间结果动态切换工具组），退回手写循环即可。

---

## 五、Builder 参数说明

| 参数 | 必填 | 说明 |
|---|---|---|
| `llm(...)` | ✅ | 支持 Function Calling 的模型，如阿里云 `qwen3.6-plus`、OpenAI `gpt-4o` |
| `tools(mcpManager, group)` | ✅ | 从 `McpManager` 加载指定分组的工具 |
| `systemPrompt(...)` | ✅ | 告诉模型它能做什么，以及任务的约束条件 |
| `maxIterations(n)` | 可选 | 最大工具调用轮次，默认 5；复杂任务可适当调大 |
| `onToolCall(...)` | 可选 | 每次 ToolCall 触发时的回调，可用于埋点、审计 |
| `onObservation(...)` | 可选 | 工具执行结果回调，可用于实时展示进度 |

---

## 六、几个实用建议

**System Prompt 要具体**。笼统的"你是智能助手"往往不够用，最好说明任务目标和约束，例如：

```
你是一个网络诊断助手，可以调用工具检测公网 IP、查询地理位置和天气。
请按需调用工具，获取足够信息后直接给出结论，不要询问用户额外信息。
```

**按业务域拆分工具组**。`mcp.config.json` 支持多个分组，建议按职责拆分，不同 Agent 加载各自需要的组：

```json
{
  "network": ["get_export_ip", "get_ip_location"],
  "weather": ["get_weather_open_meteo"],
  "crm":     ["query_customer", "update_order"]
}
```

这样做有两个好处：模型不会因为看到不相关的工具而产生错误调用；每个分组的工具列表更短，Function Calling 的准确率更高。

**`maxIterations` 留有余量但不要过大**。一般按预估最大 ToolCall 次数 +2 来设置。设得过大时，如果模型陷入重复调用，会消耗更多 Token 才能触发终止条件。

---

## 七、总结

`McpAgentExecutor` 解决的问题很直接：上篇手写的那 60 行样板代码，现在 10 行搞定，内部机制完全相同。

工具的维护和 Agent 的逻辑彻底分离——改工具只需改 `mcp.config.json`，改 Agent 行为只需改 `systemPrompt` 和 `maxIterations`，两者互不影响。

如果你需要让 Agent 同时操作文件系统、数据库等 NPX MCP 服务器，下一篇会介绍 `McpClient` 版本的 `McpAgentExecutor`，接入方式与本篇基本一致。

---

> 📎 相关资源
> - 完整示例：[Article12McpManagerAgent.java](/src/test/java/org/salt/jlangchain/demo/article/Article12McpManagerAgent.java)，对应方法 `mcpManagerAgent()`
> - 工具配置：[mcp.config.json](/src/test/resources/mcp.config.json)
> - j-langchain GitHub：[https://github.com/flower-trees/j-langchain](https://github.com/flower-trees/j-langchain)
> - j-langchain Gitee 镜像：[https://gitee.com/flower-trees-z/j-langchain](https://gitee.com/flower-trees-z/j-langchain)
> - 运行环境：需配置阿里云 API Key，示例模型 `qwen3.6-plus`
