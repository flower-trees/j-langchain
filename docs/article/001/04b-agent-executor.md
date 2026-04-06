# AgentExecutor：用一行代码启动 ReAct Agent

> **前置文章**：[Java 实现 ReAct Agent：工具调用与推理循环](04-react-agent.md)  
> **适合人群**：读完上篇、想直接用封装好的 Agent 的 Java 开发者  
> **核心概念**：AgentExecutor、Builder 模式、框架封装 vs 手动构建

---

## 问题：ReAct 有没有更简洁的写法？

上篇文章完整展示了 ReAct 的推理循环——截断 Observation、解析 Action、执行工具、拼接 scratchpad……
这些步骤对于理解原理很有价值，但在项目中重复编写就显得繁琐。

j-langchain 提供了 `AgentExecutor` 封装类，把上篇 150 行的手动代码压缩成 10 行，同时保留全部能力。

---

## 对比：手动构建 vs AgentExecutor

**手动构建（上篇方式）**：
```java
// 需要自己写：截断处理器、解析处理器、工具执行器、scratchpad 拼接、Final Answer 提取……
FlowInstance agentChain = chainActor.builder()
    .next(prompt)
    .loop(shouldContinue, llm, chainActor.builder()
        .next(cutAtObservation)
        .next(parseAction)
        .next(Info.c(needsToolCall, executeTool), Info.c(...))
        .build())
    .next(new StrOutputParser())
    .next(extractFinalAnswer)
    .build();
ChatGeneration result = chainActor.invoke(agentChain, Map.of("input", "上海现在的天气怎么样？"));
```

**AgentExecutor（本篇方式）**：
```java
ChatGeneration result = AgentExecutor.builder(chainActor)
    .llm(ChatAliyun.builder().model("qwen-plus").temperature(0f).build())
    .tools(getWeather, getTime)
    .build()
    .invoke("上海现在的天气怎么样？");
```

两者执行结果完全一致，内部机制也完全相同。

---

## Step 1：定义工具

工具定义与上篇完全一致：

```java
Tool getWeather = Tool.builder()
    .name("get_weather")
    .params("location: String")
    .description("获取城市天气信息，输入城市名称")
    .func(location -> String.format("%s 天气晴，气温 25°C", location))
    .build();

Tool getTime = Tool.builder()
    .name("get_time")
    .params("city: String")
    .description("获取城市当前时间，输入城市名称")
    .func(city -> String.format("%s 当前时间 14:30", city))
    .build();
```

---

## Step 2：构建 AgentExecutor

```java
AgentExecutor agent = AgentExecutor.builder(chainActor)
    .llm(ChatAliyun.builder().model("qwen-plus").temperature(0f).build())
    .tools(getWeather, getTime)
    .maxIterations(10)                                           // 可选，默认 10
    .onThought(System.out::print)                               // 可选：打印推理过程
    .onObservation(obs -> System.out.println("Observation: " + obs)) // 可选：打印工具结果
    .build();
```

Builder 参数说明：

| 参数 | 必填 | 说明 |
|------|------|------|
| `llm(...)` | ✅ | 任意 `BaseChatModel` 实现（Ollama、阿里云、OpenAI 等） |
| `tools(...)` | ✅ | 工具列表，至少一个 |
| `maxIterations(n)` | 可选 | 最大推理轮次，默认 10 |
| `promptTemplate(...)` | 可选 | 自定义 ReAct Prompt，需包含 `${tools}`、`${toolNames}`、`${maxIterations}`、`${input}` |
| `onThought(...)` | 可选 | 每轮 Thought/Action 生成后的回调，可用于日志、调试 |
| `onObservation(...)` | 可选 | 工具执行完成后的回调 |

---

## Step 3：执行

```java
ChatGeneration result = agent.invoke("上海现在的天气怎么样？");
System.out.println(result.getText());
```

---

## 完整执行示例

```
Thought: 我需要查询上海的天气，使用 get_weather 工具。
Action: get_weather
Action Input: 上海

Observation: 上海 天气晴，气温 25°C

Thought: 我已经获得了上海的天气信息，可以回答问题了。
Final Answer: 上海现在天气晴朗，气温 25°C。

=== 最终答案 ===
上海现在天气晴朗，气温 25°C。
```

---

## 需要深度定制时怎么办？

`AgentExecutor` 覆盖了大多数常见场景。遇到以下情况时，退回上篇的手动构建方式：

- **多 LLM 协作**：第一轮用快模型分类，后续轮次用强模型推理
- **自定义终止条件**：除工具调用判断外，还需要检查外部状态
- **循环内插入审计**：每次工具调用前后需要写日志或触发告警
- **非标准 scratchpad 格式**：对接特定模型的私有 Prompt 格式

这两种方式可以共存：先用 `AgentExecutor` 快速验证，确认需要深度定制再切换到手动模式。

---

## 与其他框架对比

| 特性 | LangChain Python | LangChain4j | j-langchain |
|------|------------------|-------------|-------------|
| 底层机制 | ReAct Prompt | Function Calling | ReAct Prompt |
| 工具定义 | `@tool` 装饰器 | `@Tool` 注解 | `Tool.builder()` |
| 简洁模式 | `create_react_agent` | `AiServices` | `AgentExecutor.builder()` |
| 手动控制循环 | 较难 | 几乎不能 | `chainActor.builder().loop(...)` |
| 模型依赖 | 任意模型 | 需支持 Function Calling | 任意模型 |
| 推理过程透明度 | 中 | 低（高度封装） | 高（全流程可见） |

> **注意**：LangChain4j 的 `AiServices` 走的是 **Function Calling** 路线，不是真正的 ReAct。  
> 模型直接输出结构化 JSON 决定调用哪个工具，框架捕获后执行，再以 `ToolMessage` 注入对话历史——本质机制不同，代码简洁度不具可比性。

---

> 完整代码见：`src/test/java/org/salt/jlangchain/demo/article/Article04ReactAgent.java` — `reactAgentWithExecutor()`
