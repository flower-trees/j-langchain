# Java 实现 ReAct Agent：工具调用与推理循环

> **适合人群**：希望让 AI 主动调用外部工具、自动完成任务的 Java 开发者  
> **核心概念**：ReAct、工具调用（Tool Use）、推理循环

---

## 什么是 Agent？

普通 LLM 是**被动的**：你问，它答，结束。

Agent 是**主动的**：它能思考、制定计划、调用工具获取信息、基于结果继续思考，直到完成目标。

**典型场景**：
- "帮我查一下上海的天气，然后推荐合适的穿搭"
- "搜索最新的 Java 21 新特性，写一份技术对比报告"
- "查询数据库中的销售数据，生成本月分析报告"

---

## ReAct 模式

ReAct = **Re**asoning + **Act**ing，交替进行推理和行动：

```
Question: 用户的问题
  ↓
Thought: LLM 思考：需要什么信息？用什么工具？
  ↓
Action: 调用工具（get_weather）
Action Input: 工具参数（"上海"）
  ↓
Observation: 工具返回结果（"上海天气晴，25°C"）
  ↓
Thought: 基于结果，还需要其他信息吗？
  ↓
（如需要，重复 Action → Observation 循环）
  ↓
Final Answer: 最终回答
```

---

## Step 1：定义工具

工具是 Agent 的"手和脚"，本质上是一个带描述的函数：

```java
// 天气查询工具
Tool getWeather = Tool.builder()
    .name("get_weather")                            // 工具名称（LLM 调用时使用）
    .params("location: String")                     // 参数描述
    .description("获取城市天气信息，输入城市名称")    // 工具描述（LLM 理解用途）
    .func(location ->                               // 实际执行逻辑
        String.format("%s 天气晴，气温 25°C", location)
    )
    .build();

// 时间查询工具
Tool getTime = Tool.builder()
    .name("get_time")
    .params("city: String")
    .description("获取城市当前时间，输入城市名称")
    .func(city -> String.format("%s 当前时间 14:30", city))
    .build();
```

> **生产中**，`func` 里可以调用真实 API、数据库查询、HTTP 请求——任何 Java 能做的事。

---

## Step 2：ReAct Prompt 模板

这是 Agent 的"大脑"，告诉 LLM 如何思考和行动：

```java
PromptTemplate prompt = PromptTemplate.fromTemplate(
    """
    尽你所能回答以下问题。你有以下工具可以使用：

    ${tools}           ← 工具列表自动注入

    请按以下格式回答：
    Question: 你必须回答的问题
    Thought: 思考是否已有足够信息回答
    Action: 要执行的动作，必须是 [${toolNames}] 之一
    Action Input: 动作的输入
    Observation: 动作结果

    （可重复 Thought/Action/Observation，最多3次）

    Final Answer: 最终回答

    Question: ${input}
    Thought:
    """
);

// 将工具注入 Prompt
List<Tool> tools = List.of(getWeather, getTime);
prompt.withTools(tools);
```

---

## Step 3：构建推理循环

这是 ReAct 的核心——循环执行，直到得到最终答案：

```java
FlowInstance agentChain = chainActor.builder()
    .next(prompt)
    .loop(
        // 循环条件：还需要调用工具 && 未超过最大次数
        shouldContinue,

        // 循环体
        llm,                   // LLM 推理（生成 Thought/Action）
        chainActor.builder()
            .next(cutAtObservation)  // 截断 LLM 自己编造的 Observation
            .next(parseAction)       // 解析 Action 和 Action Input
            .next(
                Info.c(needsToolCall, executeTool),  // 需要工具调用
                Info.c(input -> ContextBus.get().getResult(llm.getNodeId())) // 已有答案，退出循环
            )
            .build()
    )
    .next(new StrOutputParser())
    .next(extractFinalAnswer)  // 提取 "Final Answer:" 后面的内容
    .build();
```

**关键细节**：

**为什么要截断 Observation？**

LLM 有时会自己"编造"工具的返回结果（Observation）而不真正调用。通过截断 `Observation:` 之前的内容，强制让框架执行真实的工具调用。

**循环退出条件**：
```java
Function<Integer, Boolean> shouldContinue = i -> {
    Map<String, String> parsed = ContextBus.get().getResult(parseAction.getNodeId());
    return i < maxIterations                   // 未超过最大次数
        && (parsed == null                     // 还没开始
            || (parsed.containsKey("Action") && parsed.containsKey("Action Input"))); // 还需要调用工具
};
```

---

## Step 4：工具执行逻辑

每次循环，如果 LLM 决定调用工具，执行器会：
1. 找到对应工具
2. 执行工具函数
3. 将 `Observation` 追加到 Prompt，构造下一轮推理上下文

```java
TranslateHandler<Object, Map<String, String>> executeTool = new TranslateHandler<>(map -> {
    // 找到 LLM 决定调用的工具
    Tool useTool = tools.stream()
        .filter(t -> t.getName().equalsIgnoreCase(map.get("Action")))
        .findFirst().orElse(null);

    // 执行工具，获取观察结果
    String observation = (String) useTool.getFunc().apply(map.get("Action Input"));
    System.out.println("Observation: " + observation);

    // 将观察结果追加到 Prompt，形成下一轮推理的上下文
    String agentScratchpad = thoughtPart + "\nObservation:" + observation + "\nThought:";
    promptValue.setText(promptValue.getText().trim() + agentScratchpad);

    return promptValue;
});
```

---

## 完整执行示例

执行 `chainActor.invoke(agentChain, Map.of("input", "上海现在的天气怎么样？"))` 的完整输出：

```
> 进入 AgentExecutor 链...

Thought: 我需要查询上海的天气。
Action: get_weather
Action Input: 上海

Observation: 上海 天气晴，气温 25°C

Thought: 我已经获得了上海的天气信息，可以回答问题了。
Final Answer: 上海现在天气晴朗，气温25摄氏度。

> 链执行完成。

=== 最终答案 ===
上海现在天气晴朗，气温25摄氏度。
```

---

## 与 LangChain Python 对比

| 特性 | LangChain Python | j-langchain Java |
|------|------------------|------------------|
| ReAct Agent | `create_react_agent` | `chainActor.builder().loop(...)` |
| 工具定义 | `@tool` 装饰器 | `Tool.builder()` |
| 循环控制 | 框架内置 | 显式 `loop(condition, ...)` |
| 透明度 | 框架封装，较黑盒 | 完全透明，可调试每一步 |

j-langchain 选择**显式**构建推理循环，好处是：每一步都在你的代码中，方便调试和定制。

---

> 完整代码见：`src/test/java/org/salt/jlangchain/demo/article/Article04ReactAgent.java`  
> 封装版 ReAct 与 `@AgentTool`：[文章 9：AgentExecutor](09-agent-executor.md)
