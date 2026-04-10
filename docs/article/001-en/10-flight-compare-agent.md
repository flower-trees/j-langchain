# Multi-step ReAct: Airline Price Comparison and Booking

> **Tags**: Java, ReAct, Agent, j-langchain, multi-step reasoning, AgentExecutor  
> **Prerequisites**: [ReAct Agent in Java](04-react-agent.md) → [AgentExecutor](09-agent-executor.md)

---

## 1. From Single Calls to Multi-step Reasoning

Real users don’t issue step-by-step commands. Example:

> “Check MU/CA/CZ prices for 2024-03-15 Shanghai → Beijing and book the cheapest ticket.”

Hidden actions: query MU, query CA, query CZ, compare, book. The user provides a goal; the agent must plan and execute the steps. That’s multi-step ReAct.

---

## 2. Tool Design

Each tool does exactly one thing and returns a clear Observation.

```java
static class FlightTools {
    private static final Map<String, Integer> MU_PRICES = ...
    private static final Map<String, Integer> CA_PRICES = ...
    private static final Map<String, Integer> CZ_PRICES = ...

    @AgentTool("查询东方航空（MU）的机票价格")
    public String queryMuFlight(...)

    @AgentTool("查询中国国际航空（CA）的机票价格")
    public String queryCaFlight(...)

    @AgentTool("查询南方航空（CZ）的机票价格")
    public String queryCzFlight(...)

    @AgentTool("确认订购机票，输入航司、出发城市、目的城市和日期")
    public String bookFlight(...)
}
```

Keeping MU/CA/CZ as separate tools avoids asking the model to pick an airline via parameters, which reduces mistakes.

---

## 3. Build the Agent

```java
@Test
public void flightCompareAndBook() {
    AgentExecutor agent = AgentExecutor.builder(chainActor)
        .llm(ChatAliyun.builder().model("qwen-plus").temperature(0f).build())
        .tools(new FlightTools())
        .maxIterations(10)
        .onThought(System.out::print)
        .onObservation(obs -> System.out.println("Observation: " + obs))
        .build();

    ChatGeneration result = agent.invoke(
        "我要订2024-03-15从上海飞北京的机票，请帮我查询东方航空、国航、南航三家的价格，选最便宜的那家帮我订票"
    );

    System.out.println("\n=== Final ===\n" + result.getText());
}
```

`maxIterations` must cover the worst-case number of tool calls plus extra buffer.

---

## 4. Reasoning Trace

```
Thought → query_mu_flight → Observation (¥980)
Thought → query_ca_flight → Observation (¥1150)
Thought → query_cz_flight → Observation (¥860)
Thought → book_flight using CZ → Observation (order id)
Final Answer summarizing all prices and booking confirmation.
```

---

## 5. Key Takeaways

- **Granularity**: one tool per action; observations share the same format.
- **Parameters**: use `@Param` descriptions (e.g., date format) to reduce JSON mistakes.
- **Iteration budget**: plan for tool count + 2–3 extra loops.
- **Observability**: hook `onThought`/`onObservation` into your logging pipeline.

---

## 6. Extend the Pattern

| Scenario | Tools | Final action |
|----------|-------|--------------|
| Procurement | Vendor quote tools × N | Place order |
| Recommendations | Fetch scores/price/inventory | Generate report |
| Data aggregation | Query multiple systems | Write summary |
| Approval flows | Qualification + rule checks | Submit/deny |

The template remains the same: collect info with multiple tools, then trigger the execution tool once ready.

---

> Full example: `Article10FlightAgent.java` (method `flightCompareAndBook`) – requires Aliyun `qwen-plus`.
