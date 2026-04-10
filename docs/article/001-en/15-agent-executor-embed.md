# Embedding AgentExecutor inside a Chain

> **Tags**: Java, ReAct, Agent, j-langchain, Chain, Travel Planner  
> **Prerequisites**: [AgentExecutor basics](09-agent-executor.md) → [Multi-step ReAct](10-flight-compare-agent.md)

---

## 1. Agents Are Not Always the Final Stage

In many systems we must preprocess user input before the agent runs and post-process its output afterwards. Because `AgentExecutor` implements `BaseRunnable`, it can be a node inside any `chainActor` pipeline.

---

## 2. Scenario

Given a list of cities:

1. **Preprocess**: expand to a structured instruction.
2. **Agent**: call weather/flight/hotel tools for each city.
3. **Post-process**: format the agent’s answer into a report.

```
Input → TranslateHandler (preprocess) → AgentExecutor → TranslateHandler (report) → Output
```

---

## 3. Tools

`TravelTools` exposes `@AgentTool` methods for weather, flight prices, and hotel averages. Replace the switch blocks with real APIs in production.

---

## 4. Full Pipeline

```java
@Test
public void planTrip() {
    AgentExecutor travelAgent = AgentExecutor.builder(chainActor)
        .llm(ChatAliyun.builder().model("qwen-plus").temperature(0f).build())
        .tools(new TravelTools())
        .maxIterations(15)
        .onThought(System.out::print)
        .onObservation(obs -> System.out.println("\nObservation: " + obs))
        .build();

    FlowInstance travelPlanChain = chainActor.builder()
        .next(new TranslateHandler<>(userInput -> {
            System.out.println("=== 解析旅行需求 ===");
            return "我从上海出发..." + userInput + "...";
        }))
        .next(travelAgent)
        .next(new TranslateHandler<>(output -> {
            System.out.println("\n=== 生成旅行报告 ===");
            String agentAnswer = ((ChatGeneration) output).getText();
            return "\n========== 旅行规划报告 ==========\n" + agentAnswer +
                "\n==================================\n以上建议由 AI 旅行助手自动生成，请结合实际情况参考。";
        }))
        .build();

    String report = chainActor.invoke(travelPlanChain, Map.of("input", "成都、西安、桂林"));
    System.out.println(report);
}
```

---

## 5. Execution Flow

1. **Preprocess** logs the interpreted requirement.
2. **AgentExecutor** runs nine ToolCalls (3 cities × 3 tools) with thoughts/observations printed via callbacks.
3. **Post-process** wraps the final answer into a report template with a disclaimer.

---

## 6. Why This Matters

Alternative approaches either cram formatting requirements into the system prompt (brittle) or sprinkle pre/post-processing logic around call sites (duplicated). Treating AgentExecutor as a node keeps responsibilities clean: preprocessing, agent logic, and formatting live in dedicated components.

---

## 7. Choosing `maxIterations`

Estimate `cities × tools + buffer`. With 3 cities and 3 tools, expect ~9 actions; add a few extra rounds for thoughts. If the number of cities varies, pick a safe upper bound.

---

## 8. Summary

Embedding `AgentExecutor` inside a chain enables “preprocess → agent → post-process” architectures, making agents reusable building blocks rather than monolithic endpoints. Swap any stage independently without touching the others.

---

> Sample: `Article15TravelAgent.java` (`planTrip`) – requires Aliyun `qwen-plus`.
