# Multi-step ReAct: Let the Agent Autonomously Compare Airline Prices and Book

> **Tags**: `Java` `ReAct` `Agent` `j-langchain` `multi-step reasoning` `tool calls` `AgentExecutor`  
> **Prerequisite**: [ReAct Agent in Java: Tool Calls and Reasoning Loop](04-react-agent.md) → [AgentExecutor: Launch a Java ReAct Agent Without Handwriting the Loop](09-agent-executor.md)  
> **Audience**: Java developers who already use `AgentExecutor` and `@AgentTool` and want to chain multiple tools to complete a full business workflow

---

## 1. From Single Calls to Multi-step Reasoning

The previous articles demonstrated one-shot tool calls — the user asks about the weather, the Agent calls `get_weather` once and it's done. Real business tasks are rarely that simple.

Consider this scenario:

> User says: "Check prices for Eastern Airlines, Air China, and China Southern for Shanghai → Beijing on 2024-03-15, and book the cheapest one."

That single sentence hides four actions: query MU, query CA, query CZ, then book the winner. The user won't confirm each step — they gave one goal and expect the Agent to handle all the reasoning and decisions autonomously.

This is the canonical **multi-step ReAct** scenario: multiple tool calls accumulate information round by round, the model decides each next action based on accumulated Observations, until the goal is reached.

---

## 2. Tool Design: One Task, Four Tools

The principle for splitting tools is **one tool does one thing and returns a clean, structured result** — this makes it easy for the model to consume multiple Observations in its subsequent Thoughts.

```java
static class FlightTools {

    // Demo data: in-memory Map simulates a flight database; replace with real APIs in production
    private static final Map<String, Integer> MU_PRICES = Map.of(
        "上海-北京", 980, "上海-广州", 1200, "上海-成都", 1450
    );
    private static final Map<String, Integer> CA_PRICES = Map.of(
        "上海-北京", 1150, "上海-广州", 1080, "上海-成都", 1380
    );
    private static final Map<String, Integer> CZ_PRICES = Map.of(
        "上海-北京", 860, "上海-广州", 1320, "上海-成都", 1560
    );

    // Three query tools with identical structure, different airlines
    @AgentTool("查询东方航空（MU）的机票价格")
    public String queryMuFlight(
            @Param("出发城市") String fromCity,
            @Param("目的城市") String toCity,
            @Param("日期，格式 YYYY-MM-DD") String date) {
        String route = fromCity + "-" + toCity;
        Integer price = MU_PRICES.get(route);
        if (price == null) {
            return String.format("东方航空暂无 %s 航线", route);
        }
        return String.format("东方航空（MU）%s %s → %s，票价 ¥%d", date, fromCity, toCity, price);
    }

    @AgentTool("查询中国国际航空（CA）的机票价格")
    public String queryCaFlight(
            @Param("出发城市") String fromCity,
            @Param("目的城市") String toCity,
            @Param("日期，格式 YYYY-MM-DD") String date) {
        String route = fromCity + "-" + toCity;
        Integer price = CA_PRICES.get(route);
        if (price == null) {
            return String.format("国航暂无 %s 航线", route);
        }
        return String.format("中国国航（CA）%s %s → %s，票价 ¥%d", date, fromCity, toCity, price);
    }

    @AgentTool("查询南方航空（CZ）的机票价格")
    public String queryCzFlight(
            @Param("出发城市") String fromCity,
            @Param("目的城市") String toCity,
            @Param("日期，格式 YYYY-MM-DD") String date) {
        String route = fromCity + "-" + toCity;
        Integer price = CZ_PRICES.get(route);
        if (price == null) {
            return String.format("南航暂无 %s 航线", route);
        }
        return String.format("南方航空（CZ）%s %s → %s，票价 ¥%d", date, fromCity, toCity, price);
    }

    // Booking tool: called by the model once comparison is complete
    @AgentTool("确认订购机票，输入航司、出发城市、目的城市和日期")
    public String bookFlight(
            @Param("航司名称，如：东方航空、国航、南航") String airline,
            @Param("出发城市") String fromCity,
            @Param("目的城市") String toCity,
            @Param("日期，格式 YYYY-MM-DD") String date) {
        return String.format("✅ 订票成功！%s %s → %s，日期 %s，订单号 ORD-%d",
            airline, fromCity, toCity, date, (long)(Math.random() * 900000) + 100000);
    }
}
```

There's a design trade-off worth noting: the three airline queries are structurally identical and could be merged into one tool with an `airline` parameter. But keeping them as three independent tools has a benefit — **the model doesn't need to decide which airline to query at the parameter level**, reducing one layer of reasoning burden and lowering error rates in complex scenarios.

---

## 3. Build the Agent — Only the Tools Change

The `AgentExecutor` setup is identical to the previous article; the only change is swapping in `FlightTools`:

```java
@Test
public void flightCompareAndBook() {

    AgentExecutor agent = AgentExecutor.builder(chainActor)
        .llm(ChatAliyun.builder().model("qwen-plus").temperature(0f).build())
        .tools(new FlightTools())   // Pass the tool class; the framework scans @AgentTool methods
        .maxIterations(10)          // Multi-step reasoning needs headroom: 3 queries + 1 booking + several Thoughts
        .onThought(System.out::print)
        .onObservation(obs -> System.out.println("Observation: " + obs))
        .build();

    ChatGeneration result = agent.invoke(
        "我要订2024-03-15从上海飞北京的机票，请帮我查询东方航空、国航、南航三家的价格，选最便宜的那家帮我订票"
    );

    System.out.println("\n=== 最终答案 ===");
    System.out.println(result.getText());
}
```

`maxIterations` is set to 10 for headroom: three query tool calls + one booking = at least 4 Actions, plus a Thought for each round, consuming roughly 6~8 iterations in practice.

---

## 4. The Agent's Complete Reasoning Trace

After running, the `onThought` and `onObservation` callbacks print the entire reasoning process:

```
Thought: 需要分别查询三家航司的票价。先查东方航空。
Action: query_mu_flight
Action Input: {"fromCity": "上海", "toCity": "北京", "date": "2024-03-15"}

Observation: 东方航空（MU）2024-03-15 上海 → 北京，票价 ¥980

Thought: 东方航空 ¥980，继续查国航。
Action: query_ca_flight
Action Input: {"fromCity": "上海", "toCity": "北京", "date": "2024-03-15"}

Observation: 中国国航（CA）2024-03-15 上海 → 北京，票价 ¥1150

Thought: 国航 ¥1150，继续查南航。
Action: query_cz_flight
Action Input: {"fromCity": "上海", "toCity": "北京", "date": "2024-03-15"}

Observation: 南方航空（CZ）2024-03-15 上海 → 北京，票价 ¥860

Thought: 三家对比：南航 ¥860 最便宜，调用订票工具。
Action: book_flight
Action Input: {"airline": "南航", "fromCity": "上海", "toCity": "北京", "date": "2024-03-15"}

Observation: ✅ 订票成功！南航 上海 → 北京，日期 2024-03-15，订单号 ORD-342871

Final Answer: 已为您查询三家航司票价：东方航空 ¥980、国航 ¥1150、南航 ¥860。
             南航价格最低，已成功订票，订单号 ORD-342871。
```

Throughout the process, the model did three things: **collected information in order** (three queries), **made a comparison in its Thought**, and **called the downstream tool once it chose the best option**. This is the core value of the ReAct framework — reasoning and action alternate, with each Observation becoming the input for the next Thought.

---

## 5. Key Design Points

**Tool granularity**: each tool does one thing and returns a fixed, uniform Observation format. The finer the granularity, the easier it is for the model to reason in its Thoughts, and the lower the error rate.

**Parameter design**: multi-parameter tools use JSON format in Action Input. The `@Param` description should be specific — especially for constrained parameters like date formats. Writing the constraint explicitly in the annotation significantly reduces the model's formatting errors.

**`maxIterations` with headroom**: estimate the worst-case number of tool calls, then add a 2~3 round buffer to avoid cutting off reasoning mid-flow. This example needs at least 4 Actions; setting 10 is a reasonable upper bound.

**Observable reasoning**: the `onThought` and `onObservation` callbacks aren't just for debugging. In production they can feed a logging system and record the complete input/output of every tool call, making issues easy to diagnose.

---

## 6. Extension Patterns

This pattern can be directly reused across many business scenarios — just swap out the tool class:

| Scenario | Tool combination | Final action |
|---|---|---|
| Procurement comparison | Multiple supplier quote queries × N | Place order |
| Smart recommendation | Multi-dimensional data queries (score / price / inventory) | Generate recommendation report |
| Data aggregation | Multi-table / multi-system queries | Write to summary table |
| Approval workflow | Eligibility query + rule validation | Submit approval / reject with reason |

The core pattern is always the same: **multiple information-gathering tools + one action tool, with the Agent autonomously deciding when to switch from "collecting" to "executing"**.

---

> 📎 Resources
> - Full example: [Article10FlightAgent.java](../../../src/test/java/org/salt/jlangchain/demo/article/Article10FlightAgent.java), method `flightCompareAndBook()`
> - j-langchain GitHub: https://github.com/flower-trees/j-langchain
> - j-langchain Gitee mirror: https://gitee.com/flower-trees-z/j-langchain
> - Runtime requirements: Aliyun API Key required, example model `qwen-plus`
