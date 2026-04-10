# Implementing a ReAct Agent in Java: Tool Calling and Reasoning Loops

> **Audience**: Java developers who want AI to call external tools and complete tasks autonomously  
> **Core concepts**: ReAct, Tool Use, reasoning loop

---

## What Is an Agent?

A regular LLM is **passive**: you ask, it answers, done.

An Agent is **active**: it can think, form a plan, call tools to gather information, and continue reasoning based on the results — until it reaches its goal.

**Typical scenarios**:
- "Check the weather in Shanghai and recommend what to wear"
- "Search for the latest Java 21 features and write a technical comparison report"
- "Query the sales data in the database and generate a monthly analysis report"

---

## The ReAct Pattern

ReAct = **Re**asoning + **Act**ing — alternating between thinking and acting:

```
Question: The user's question
  ↓
Thought: The LLM thinks: what information do I need? Which tool should I use?
  ↓
Action: Call a tool (get_weather)
Action Input: Tool parameters ("Shanghai")
  ↓
Observation: Tool result ("Shanghai is sunny, 25°C")
  ↓
Thought: Based on the result, do I need more information?
  ↓
(Repeat Action → Observation as needed)
  ↓
Final Answer: The final response
```

---

## Step 1: Define Tools

Tools are the Agent's "hands and feet" — essentially a function with a description:

```java
// Weather query tool
Tool getWeather = Tool.builder()
    .name("get_weather")                            // Tool name (used by the LLM when calling)
    .params("location: String")                     // Parameter description
    .description("Get weather information for a city. Input: city name.") // Description for the LLM
    .func(location ->                               // Actual execution logic
        String.format("%s: sunny, 25°C", location)
    )
    .build();

// Time query tool
Tool getTime = Tool.builder()
    .name("get_time")
    .params("city: String")
    .description("Get the current time for a city. Input: city name.")
    .func(city -> String.format("%s current time: 14:30", city))
    .build();
```

> **In production**, the `func` can call real APIs, query databases, make HTTP requests — anything Java can do.

---

## Step 2: The ReAct Prompt Template

This is the Agent's "brain" — it tells the LLM how to think and act:

```java
PromptTemplate prompt = PromptTemplate.fromTemplate(
    """
    Answer the following question as best you can. You have access to the following tools:

    ${tools}           ← tool list is automatically injected

    Use the following format:
    Question: the input question you must answer
    Thought: think about whether you already have enough information
    Action: the action to take, must be one of [${toolNames}]
    Action Input: the input to the action
    Observation: the result of the action

    (You may repeat Thought/Action/Observation up to 3 times)

    Final Answer: the final answer

    Question: ${input}
    Thought:
    """
);

// Inject tools into the Prompt
List<Tool> tools = List.of(getWeather, getTime);
prompt.withTools(tools);
```

---

## Step 3: Build the Reasoning Loop

This is the core of ReAct — loop until a final answer is reached:

```java
FlowInstance agentChain = chainActor.builder()
    .next(prompt)
    .loop(
        // Loop condition: still needs tool calls && hasn't exceeded the max iterations
        shouldContinue,

        // Loop body
        llm,                   // LLM reasoning (generates Thought/Action)
        chainActor.builder()
            .next(cutAtObservation)  // Truncate the LLM's self-fabricated Observation
            .next(parseAction)       // Parse Action and Action Input
            .next(
                Info.c(needsToolCall, executeTool),  // Tool call needed
                Info.c(input -> ContextBus.get().getResult(llm.getNodeId())) // Answer ready, exit loop
            )
            .build()
    )
    .next(new StrOutputParser())
    .next(extractFinalAnswer)  // Extract content after "Final Answer:"
    .build();
```

**Key details**:

**Why truncate Observation?**

LLMs sometimes "fabricate" a tool result (Observation) rather than actually calling the tool. By truncating everything after `Observation:`, we force the framework to execute the real tool call.

**Loop exit condition**:
```java
Function<Integer, Boolean> shouldContinue = i -> {
    Map<String, String> parsed = ContextBus.get().getResult(parseAction.getNodeId());
    return i < maxIterations                   // hasn't exceeded max iterations
        && (parsed == null                     // hasn't started yet
            || (parsed.containsKey("Action") && parsed.containsKey("Action Input"))); // still needs a tool call
};
```

---

## Step 4: Tool Execution Logic

On each iteration, if the LLM decides to call a tool, the executor will:
1. Find the corresponding tool
2. Execute the tool function
3. Append the `Observation` to the Prompt to build the context for the next reasoning step

```java
TranslateHandler<Object, Map<String, String>> executeTool = new TranslateHandler<>(map -> {
    // Find the tool the LLM decided to call
    Tool useTool = tools.stream()
        .filter(t -> t.getName().equalsIgnoreCase(map.get("Action")))
        .findFirst().orElse(null);

    // Execute the tool and get the observation
    String observation = (String) useTool.getFunc().apply(map.get("Action Input"));
    System.out.println("Observation: " + observation);

    // Append the observation to the Prompt to form the context for the next reasoning step
    String agentScratchpad = thoughtPart + "\nObservation:" + observation + "\nThought:";
    promptValue.setText(promptValue.getText().trim() + agentScratchpad);

    return promptValue;
});
```

---

## Complete Execution Example

The full output of `chainActor.invoke(agentChain, Map.of("input", "What is the weather like in Shanghai right now?"))`:

```
> Entering AgentExecutor chain...

Thought: I need to check the weather in Shanghai.
Action: get_weather
Action Input: Shanghai

Observation: Shanghai: sunny, 25°C

Thought: I now have the weather information for Shanghai and can answer the question.
Final Answer: The weather in Shanghai is currently sunny with a temperature of 25°C.

> Chain execution complete.

=== Final Answer ===
The weather in Shanghai is currently sunny with a temperature of 25°C.
```

---

## Comparison with LangChain Python

| Feature | LangChain Python | j-langchain Java |
|---------|------------------|------------------|
| ReAct Agent | `create_react_agent` | `chainActor.builder().loop(...)` |
| Tool definition | `@tool` decorator | `Tool.builder()` |
| Loop control | Built into the framework | Explicit `loop(condition, ...)` |
| Transparency | Framework-encapsulated, relatively opaque | Fully transparent, every step is debuggable |

j-langchain chooses to **explicitly** build the reasoning loop. The benefit: every step is in your own code, making it easy to debug and customize.

---

> Full source code: `src/test/java/org/salt/jlangchain/demo/article/Article04ReactAgent.java`  
> Packaged ReAct with `@AgentTool`: [Article 9: AgentExecutor](09-agent-executor.md)
