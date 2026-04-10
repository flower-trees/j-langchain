# ReAct Agents in Java: Tool Calls and Reasoning Loops

> **Audience**: Java developers who want AI to call external tools and complete tasks autonomously  
> **Core ideas**: ReAct, tool use, reasoning loop

---

## What Is an Agent?

A regular LLM is **passive**: you ask, it answers, and that’s it.

An agent is **proactive**: it can reason, plan, call tools, inspect the results, and continue until the goal is met.

**Examples**
- “Check today’s weather in Shanghai and suggest an outfit.”
- “Search the latest Java 21 features and draft a comparison report.”
- “Query the sales database and produce this month’s analysis.”

---

## ReAct

ReAct = **Re**asoning + **Act**ing—alternating thinking and action:

```
Question → Thought → Action → Action Input → Observation
           ↘ (repeat) ↙
Final Answer
```

---

## Step 1: Define Tools

Tools are the agent’s “hands and feet”—essentially functions with metadata:

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

> In production, `func` can hit real APIs, databases, HTTP services—anything Java can execute.

---

## Step 2: ReAct Prompt

The prompt is the “brain” that tells the LLM how to think and act:

```java
PromptTemplate prompt = PromptTemplate.fromTemplate(
    """
    尽你所能回答以下问题。你有以下工具可以使用：

    ${tools}

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

List<Tool> tools = List.of(getWeather, getTime);
prompt.withTools(tools);
```

---

## Step 3: Build the Reasoning Loop

This is the heart of ReAct—repeat until the agent reaches the final answer:

```java
FlowInstance agentChain = chainActor.builder()
    .next(prompt)
    .loop(
        shouldContinue,
        llm,
        chainActor.builder()
            .next(cutAtObservation)
            .next(parseAction)
            .next(
                Info.c(needsToolCall, executeTool),
                Info.c(input -> ContextBus.get().getResult(llm.getNodeId()))
            )
            .build()
    )
    .next(new StrOutputParser())
    .next(extractFinalAnswer)
    .build();
```

**Why cut at Observation?** LLMs sometimes fabricate Observation text without calling your tools. By trimming everything after `Observation:`, you ensure the framework performs the real call.

**Loop exit condition**

```java
Function<Integer, Boolean> shouldContinue = i -> {
    Map<String, String> parsed = ContextBus.get().getResult(parseAction.getNodeId());
    return i < maxIterations &&
        (parsed == null || (parsed.containsKey("Action") && parsed.containsKey("Action Input")));
};
```

---

## Step 4: Execute Tools

Whenever the LLM decides to call a tool, the executor will:
1. Find the tool
2. Run it
3. Append the Observation back into the prompt for the next iteration

```java
TranslateHandler<Object, Map<String, String>> executeTool = new TranslateHandler<>(map -> {
    Tool useTool = tools.stream()
        .filter(t -> t.getName().equalsIgnoreCase(map.get("Action")))
        .findFirst().orElse(null);

    String observation = (String) useTool.getFunc().apply(map.get("Action Input"));
    System.out.println("Observation: " + observation);

    String agentScratchpad = thoughtPart + "\nObservation:" + observation + "\nThought:";
    promptValue.setText(promptValue.getText().trim() + agentScratchpad);

    return promptValue;
});
```

---

## End-to-End Run

Calling `chainActor.invoke(agentChain, Map.of("input", "上海现在的天气怎么样？"))` outputs:

```
Thought: 我需要查询上海的天气。
Action: get_weather
Action Input: 上海

Observation: 上海 天气晴，气温 25°C

Thought: 我已经获得了上海的天气信息，可以回答问题了。
Final Answer: 上海现在天气晴朗，气温25摄氏度。
```

---

## LangChain Python vs. j-langchain

| Feature | LangChain Python | j-langchain |
|---------|------------------|-------------|
| ReAct Agent | `create_react_agent` | `chainActor.builder().loop(...)` |
| Tool definition | `@tool` decorator | `Tool.builder()` |
| Loop control | Built in | Explicit `loop(condition, …)` |
| Transparency | More black-box | Fully transparent and debuggable |

j-langchain deliberately exposes every step of the reasoning loop so you can debug and customize with full control.

---

> Full code: `src/test/java/org/salt/jlangchain/demo/article/Article04ReactAgent.java`  
> Higher-level wrapper with `@AgentTool`: see [Article 9 – AgentExecutor](09-agent-executor.md)
