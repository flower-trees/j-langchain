# Three Agents in Parallel: Building a Fan-out / Fan-in Travel Planner with `concurrent`

> **Tags**: `Java` `Agent` `ReAct` `j-langchain` `multi-agent` `parallel` `concurrent` `travel planning` `AgentExecutor`  
> **Prerequisite**: [Dual-Agent Pipeline: Building a Customer Service Ticket Workflow with an Analysis Agent + Execution Agent](16-multi-agent-executor.md)  
> **Audience**: Java developers who know multi-Agent sequential pipelines and want to run multiple Agents in parallel to improve efficiency

---

## 1. The Limitation of Sequential Execution: Why Independent Tasks Shouldn't Queue

The previous article demonstrated the classic sequential dual-Agent pattern: the Analysis Agent finishes, then hands its conclusion to the Execution Agent. This design's premise is **a data dependency between the two tasks** — the Execution Agent needs the analysis conclusion before it can work.

But there's another class of scenarios where tasks have **no data dependency** and are completely independent of each other:

Take travel planning as an example. The user requests "5 days / 4 nights in Kyoto," and the system needs to research from three dimensions:

- **Attractions**: what are the top sights? what local specialties are there?
- **Weather**: what's the weather like in Kyoto in April? any travel advice?
- **Budget**: how much are flights? how to choose accommodation? estimated daily spend?

These three dimensions don't depend on each other. Attraction research doesn't need to wait for weather research to finish; budget calculations don't need to wait for attraction results. If you run them sequentially, the total time is the sum of all three; running them in parallel, the total time is only as long as the slowest one.

**The essential difference between sequential and parallel:**

| Mode | When to use | Total time |
|---|---|---|
| Sequential (next → next) | Tasks have data dependencies; B needs A's result | T_A + T_B + T_C |
| Parallel (concurrent) | Tasks have no data dependencies; A/B/C can start simultaneously | max(T_A, T_B, T_C) |

---

## 2. Overall Architecture

```
User travel request (Kyoto, 5 days / 4 nights, April)
     ↓
TranslateHandler (pre-process: format as specialized research tasks)
     ↓
┌────────────────────────────────────────┐
│  concurrent parallel node              │
│  ┌──────────────┐                      │
│  │ Attraction Agent │ → attractions & food report    │
│  │ Weather Agent    │ → weather & travel advice      │
│  │ Budget Agent     │ → flights, hotels & budget     │
│  └──────────────┘                      │
└────────────────────────────────────────┘
     ↓ Map<alias, ChatGeneration>
next lambda (merge: combine three reports, assemble synthesis prompt)
     ↓
AgentExecutor (Synthesis Agent: generate complete itinerary)
     ↓
TranslateHandler (post-process: format final output)
     ↓
Travel plan
```

Three specialist Agents research in parallel; their results are merged through a fan-in node, then handed to the Synthesis Agent to produce the complete plan. This "fan-out / fan-in" topology is the most typical structure for parallel Agents.

---

## 3. Tool Definitions for Each Agent

Each Agent has its own domain-specific tools; tool sets are completely isolated with no overlap.

### Attraction Tools

```java
static class AttractionTools {

    @AgentTool("查询目的地热门景点")
    public String getTopAttractions(@Param("城市名称，如：京都") String city) {
        return switch (city) {
            case "京都" -> "京都热门景点：金阁寺、岚山竹林、伏见稻荷大社、清水寺、哲学之道、祇园花见小路";
            case "东京" -> "东京热门景点：浅草寺、新宿御苑、皇居、秋叶原、涩谷十字路口";
            default -> city + "热门景点：历史文化区、著名寺庙、传统市场、自然公园";
        };
    }

    @AgentTool("查询目的地特色美食")
    public String getLocalCuisine(@Param("城市名称，如：京都") String city) {
        return switch (city) {
            case "京都" -> "京都特色美食：汤豆腐、怀石料理、京野菜、抹茶甜点、鸭川河边料理、锦市场小吃";
            default -> city + "特色美食：当地传统料理、新鲜海鲜、街边小吃";
        };
    }
}
```

### Weather Tools

```java
static class WeatherTools {

    @AgentTool("查询目的地指定月份天气")
    public String getWeatherByMonth(
            @Param("城市名称，如：京都") String city,
            @Param("出行月份，如：4月") String month) {
        if ("京都".equals(city)) {
            return switch (month) {
                case "3月", "4月" -> "京都" + month + "：樱花季，气温 10~20°C，晴天为主，人流量极大，建议提前3个月预订住宿";
                case "11月" -> "京都11月：红叶季，气温 8~18°C，色彩最美";
                default -> "京都" + month + "：气温适中，人流平稳，适合游览";
            };
        }
        return city + month + "：气温适中，天气较好，适合出行";
    }

    @AgentTool("查询目的地出行注意事项")
    public String getTravelAdvice(
            @Param("城市名称，如：京都") String city,
            @Param("出行月份，如：4月") String month) {
        if ("京都".equals(city) && ("3月".equals(month) || "4月".equals(month))) {
            return "京都4月出行建议：" +
                "①提前3个月预订住宿，樱花季房价是平时2~3倍；" +
                "②工作日前往热门景点人更少；" +
                "③推荐路线：岚山→金阁寺→祇园→清水寺→伏见稻荷（2天起步）；" +
                "④建议购买IC交通卡，公交比计程车便宜3倍以上";
        }
        return city + month + "出行建议：提前规划行程，注意当地文化礼仪，避开本地节假日高峰";
    }
}
```

### Budget Tools

```java
static class BudgetTools {

    @AgentTool("查询国际往返机票价格")
    public String getFlightCost(
            @Param("目的地城市，如：京都") String destination,
            @Param("出行月份，如：4月") String month) {
        if ("京都".equals(destination) && ("3月".equals(month) || "4月".equals(month))) {
            return "上海→大阪关西机场（距京都最近）樱花季：经济舱往返 ¥2800~4500，需提前2个月购买；" +
                "大阪→京都：JR新干线 ¥75/人单程，约15分钟";
        }
        return "上海→" + destination + " " + month + "机票：经济舱往返约 ¥2200~3500，建议提前1个月购买";
    }

    @AgentTool("查询目的地住宿费用")
    public String getHotelCost(
            @Param("城市名称，如：京都") String city,
            @Param("住宿晚数，如：4晚") String nights) {
        return switch (city) {
            case "京都" -> "京都住宿（" + nights + "）：经济型民宿 ¥200~400/晚，中端酒店 ¥600~1200/晚，高端和式旅馆 ¥2000+/晚；" +
                "樱花季建议提前预订，旺季涨价明显";
            default -> city + "住宿（" + nights + "）：均价 ¥500~1000/晚";
        };
    }

    @AgentTool("查询目的地每日餐饮与交通费用")
    public String getDailyExpense(@Param("城市名称，如：京都") String city) {
        return switch (city) {
            case "京都" -> "京都每日开销：餐饮 ¥100~300，交通 ¥50~100（巴士一日券¥70），景点门票 ¥50~200，合计约 ¥200~600/天";
            default -> city + "每日开销：约 ¥200~500/天";
        };
    }
}
```

### Synthesis Planning Tool

The Synthesis Agent needs just one tool — getting the current time to timestamp the generated plan:

```java
static class PlanTools {

    @AgentTool("获取当前规划生成时间")
    public String getPlanTimestamp() {
        return "规划生成时间：" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy年MM月dd日 HH:mm"));
    }
}
```

---

## 4. Building the Parallel Pipeline

```java
@Test
public void parallelTravelResearch() {

    // ── Agent 1: Attraction Agent ─────────────────────────────────────────────
    AgentExecutor attractionAgent = AgentExecutor.builder(chainActor)
        .llm(ChatAliyun.builder().model("qwen-plus").temperature(0f).build())
        .tools(new AttractionTools())
        .maxIterations(6)
        .onThought(t -> System.out.println("[景点Agent] " + t))
        .onObservation(obs -> System.out.println("[景点结果] " + obs))
        .build();

    // ── Agent 2: Weather Agent ────────────────────────────────────────────────
    AgentExecutor weatherAgent = AgentExecutor.builder(chainActor)
        .llm(ChatAliyun.builder().model("qwen-plus").temperature(0f).build())
        .tools(new WeatherTools())
        .maxIterations(6)
        .onThought(t -> System.out.println("[天气Agent] " + t))
        .onObservation(obs -> System.out.println("[天气结果] " + obs))
        .build();

    // ── Agent 3: Budget Agent ─────────────────────────────────────────────────
    AgentExecutor budgetAgent = AgentExecutor.builder(chainActor)
        .llm(ChatAliyun.builder().model("qwen-plus").temperature(0f).build())
        .tools(new BudgetTools())
        .maxIterations(8)
        .onThought(t -> System.out.println("[费用Agent] " + t))
        .onObservation(obs -> System.out.println("[费用结果] " + obs))
        .build();

    // ── Agent 4: Synthesis Agent ──────────────────────────────────────────────
    AgentExecutor synthesisAgent = AgentExecutor.builder(chainActor)
        .llm(ChatAliyun.builder().model("qwen-plus").temperature(0.7f).build())
        .tools(new PlanTools())
        .maxIterations(3)
        .onThought(t -> System.out.println("[综合Agent] " + t))
        .onObservation(obs -> System.out.println("[综合结果] " + obs))
        .build();

    // ── Parallel pipeline ─────────────────────────────────────────────────────
    FlowInstance travelPlan = chainActor.builder()

        // Node 1: pre-process — convert user input into specialized research task descriptions
        .next(new TranslateHandler<>(userInput -> {
            System.out.println("\n========== 收到旅游规划请求 ==========");
            System.out.println(userInput);
            System.out.println("\n--- 启动三路并行专项调研 ---");
            return "旅游需求：" + userInput +
                "\n请根据你的专业领域，针对该目的地提供详细的专项分析和建议。";
        }))

        // Node 2: parallel execution — three Agents run simultaneously and independently
        .concurrent(
            600000, // timeout: 10 minutes
            Info.c(attractionAgent).cAlias("attraction"),
            Info.c(weatherAgent).cAlias("weather"),
            Info.c(budgetAgent).cAlias("budget")
        )

        // Node 3: merge — combine three results and assemble a synthesis prompt
        .next(map -> {
            Map<String, Object> results = (Map<String, Object>) map;
            String attractionReport = ((ChatGeneration) results.get("attraction")).getText();
            String weatherReport    = ((ChatGeneration) results.get("weather")).getText();
            String budgetReport     = ((ChatGeneration) results.get("budget")).getText();

            System.out.println("\n--- 三路调研完成，移交综合规划 Agent ---");

            return "请先获取当前规划时间，然后根据以下三份专项调研报告，制定完整的旅行规划方案：\n\n" +
                "=== 景点与体验报告 ===\n" + attractionReport + "\n\n" +
                "=== 天气与时节报告 ===\n" + weatherReport + "\n\n" +
                "=== 费用与预算报告 ===\n" + budgetReport + "\n\n" +
                "请输出：1)每日行程安排；2)预算汇总表；3)出行注意事项。";
        })

        // Node 4: Synthesis Agent — generate the complete travel plan from three reports
        .next(synthesisAgent)

        // Node 5: post-process — format final output
        .next(new TranslateHandler<>(output -> {
            String plan = ((ChatGeneration) output).getText();
            return "\n========== 旅行规划方案 ==========\n" + plan +
                "\n==================================";
        }))

        .build();

    String result = chainActor.invoke(travelPlan, Map.of(
        "input", "京都，5天4夜，4月出发，预算适中"
    ));

    System.out.println(result);
}
```

---

## 5. Complete Execution Trace

**Phase 1: Receive the request**
```
========== 收到旅游规划请求 ==========
京都，5天4夜，4月出发，预算适中

--- 启动三路并行专项调研 ---
```

**Phase 2: Three Agents run in parallel (outputs interleave)**
```
[景点Agent] Thought: 需要查询京都的热门景点。
Action: get_top_attractions
Action Input: {"city": "京都"}
[景点结果] 京都热门景点：金阁寺、岚山竹林、伏见稻荷大社、清水寺...

[天气Agent] Thought: 需要查询京都4月的天气情况。
Action: get_weather_by_month
Action Input: {"city": "京都", "month": "4月"}
[天气结果] 京都4月：樱花季，气温 10~20°C，晴天为主，人流量极大...

[费用Agent] Thought: 需要查询机票、住宿和每日开销。
Action: get_flight_cost
Action Input: {"destination": "京都", "month": "4月"}
[费用结果] 上海→大阪关西机场樱花季：经济舱往返 ¥2800~4500...

[景点Agent] Thought: 还需要查询当地美食。
Action: get_local_cuisine
Action Input: {"city": "京都"}
[景点结果] 京都特色美食：汤豆腐、怀石料理、京野菜、抹茶甜点...

[费用Agent] Thought: 继续查询住宿和每日开销。
Action: get_hotel_cost
Action Input: {"city": "京都", "nights": "4晚"}
[费用结果] 京都住宿（4晚）：经济型民宿 ¥200~400/晚...

[天气Agent] Thought: 还需要给出出行建议。
Action: get_travel_advice
Action Input: {"city": "京都", "month": "4月"}
[天气结果] 京都4月出行建议：①提前3个月预订住宿...

[费用Agent] Action: get_daily_expense
Action Input: {"city": "京都"}
[费用结果] 京都每日开销：餐饮 ¥100~300，交通 ¥50~100...

[景点Agent] Final Answer: 京都热门景点包括金阁寺、岚山竹林...
[天气Agent] Final Answer: 京都4月正值樱花季，气温宜人...
[费用Agent] Final Answer: 5天4夜京都行预算估算...
```

**Phase 3: Merge and hand off to Synthesis Agent**
```
--- 三路调研完成，移交综合规划 Agent ---
[综合Agent] Thought: 先获取当前时间，再制定规划。
Action: get_plan_timestamp
[综合结果] 规划生成时间：2025年04月15日 14:32

[综合Agent] Final Answer: 
规划生成时间：2025年04月15日 14:32

=== 京都5天4夜樱花季旅行规划 ===

【每日行程】
第1天：抵达大阪→JR前往京都→岚山竹林→天龙寺→嵯峨野小火车
第2天：金阁寺→仁和寺→龙安寺→哲学之道→南禅寺
第3天：伏见稻荷大社（建议清晨6点前）→月桂冠大仓纪念馆→锦市场
第4天：清水寺→产宁坂→祇园花见小路→八坂神社→先斗町晚餐
第5天：上午自由活动→下午前往大阪关西机场返程

【预算汇总】
- 往返机票：¥3500（预估，提前购买）
- 住宿4晚：¥2400（中端酒店¥600/晚）
- 每日开销：¥400×5天 = ¥2000
- 合计：约 ¥7900/人

【注意事项】
1. 住宿务必提前3个月预订，樱花季一房难求
2. 购买IC卡（Suica/ICOCA），公交出行更便捷
3. 伏见稻荷大社清晨人少，避开白天高峰
4. 怀石料理建议提前预约，人均¥500+
```

**Phase 4: Final output**
```
========== 旅行规划方案 ==========
（full plan content above）
==================================
```

---

## 6. Two Key Design Points for the Parallel Node

### 1. `cAlias`: Naming Parallel Results

After `concurrent` completes, it returns a `Map<String, Object>` with one entry per parallel node. Without `cAlias`, the keys are internally generated UUIDs — the merge node has no way to retrieve values by meaningful names:

```java
// ❌ Without cAlias: keys are internal UUIDs, unusable by name
.concurrent(attractionAgent, weatherAgent, budgetAgent)
.next(map -> {
    // map keys look like "org.salt.jlangchain.core.agent.AgentExecutor@3a1b2c3d"
})

// ✅ With cAlias: keys are meaningful names
.concurrent(
    Info.c(attractionAgent).cAlias("attraction"),
    Info.c(weatherAgent).cAlias("weather"),
    Info.c(budgetAgent).cAlias("budget")
)
.next(map -> {
    String report = ((ChatGeneration) map.get("attraction")).getText(); // clear and direct
})
```

### 2. The Merge Node's Responsibilities: Three Tasks That Cannot Be Omitted

The merge node (Node 3's lambda) must handle three things:

**Extract**: pull each Agent's `ChatGeneration` from `Map<String, Object>` and call `.getText()` to get the text report.

**Assemble**: concatenate the three reports into a prompt the Synthesis Agent can understand, with clear section titles so the model doesn't have to guess which part covers which dimension.

**Specify the task**: explicitly tell the Synthesis Agent what to output (daily itinerary + budget summary + travel tips). Don't just dump three reports on it and expect it to decide the output format on its own.

```java
.next(map -> {
    // Extract
    String attractionReport = ((ChatGeneration) map.get("attraction")).getText();
    String weatherReport    = ((ChatGeneration) map.get("weather")).getText();
    String budgetReport     = ((ChatGeneration) map.get("budget")).getText();

    // Assemble + specify task
    return "请先获取当前规划时间，然后根据以下三份专项调研报告，制定完整的旅行规划方案：\n\n" +
        "=== 景点与体验报告 ===\n" + attractionReport + "\n\n" +
        "=== 天气与时节报告 ===\n" + weatherReport + "\n\n" +
        "=== 费用与预算报告 ===\n" + budgetReport + "\n\n" +
        "请输出：1)每日行程安排；2)预算汇总表；3)出行注意事项。";
})
```

The merge node is the most underestimated part of a parallel pipeline — the quality of the three-report assembly directly determines the quality of the Synthesis Agent's output.

---

## 7. Timeout Control

When three Agents run in parallel, the framework waits for all Agents to complete (or time out). The default timeout is 3000ms, which is usually not enough for external LLM calls. You can specify a longer timeout in `concurrent` (in milliseconds):

```java
.concurrent(
    5000L,   // wait at most 5 seconds
    Info.c(attractionAgent).cAlias("attraction"),
    Info.c(weatherAgent).cAlias("weather"),
    Info.c(budgetAgent).cAlias("budget")
)
```

After a timeout, the unfinished Agent's result does not enter the merge Map. The merge node receives a Map missing the corresponding key, so defensive handling is needed in the merge logic:

```java
.next(map -> {
    Map<String, Object> results = (Map<String, Object>) map;
    String attractionReport = results.containsKey("attraction")
        ? ((ChatGeneration) results.get("attraction")).getText()
        : "（景点信息获取超时，请稍后重试）";
    // ... same for the others
})
```

---

## 8. Sequential vs Parallel: How to Choose

| Dimension | Sequential (next → next) | Parallel (concurrent) |
|---|---|---|
| When to use | The next step needs the previous step's result | Each step's input comes from the same upstream; steps are independent |
| Typical example | Analyze before executing, approve before notifying | Multi-dimensional data collection, multi-model voting |
| Total time | T_A + T_B + T_C | max(T_A, T_B, T_C) |
| Tool design | Tools across Agents can be related | Each Agent's tool set is completely independent |
| Merge node | TranslateHandler does format conversion | Merge Map, assemble combined prompt |

This article's pattern — three parallel Agents + one synthesis Agent — is the basic form of fan-out / fan-in. For more complex topologies (e.g., first parallel collection, then sequential decision-making, then parallel notification), just combine `.concurrent()`, `.next()`, and `.notify()` nodes in `chainActor.builder()`, with each type of node handling its own responsibility.

---

## 9. Summary

This article demonstrated the complete parallel Agent usage in j-langchain:

- `concurrent()` lets multiple `AgentExecutor` instances run simultaneously, using `CountDownLatch` under the hood to wait for all results
- `Info.c(agent).cAlias("key")` names each parallel Agent's result; the merge node retrieves values by key precisely
- The merge lambda (`.next(map -> {...})`) handles extraction, assembly, and task specification
- Timeout can be specified in `concurrent(timeout, ...)` to prevent a single Agent from blocking the entire flow

Together with sequential multi-Agent, these two patterns cover the vast majority of multi-Agent collaboration scenarios: **sequential handles forward dependencies; parallel handles independent collection**.

---

> 📎 Resources
> - Full example: [Article18ParallelTravelResearch.java](../../../src/test/java/org/salt/jlangchain/demo/article/Article18ParallelTravelResearch.java), method `parallelTravelResearch()`
> - j-langchain GitHub: https://github.com/flower-trees/j-langchain
> - j-langchain Gitee mirror: https://gitee.com/flower-trees-z/j-langchain
> - Runtime requirements: Aliyun API Key required (`ALIYUN_KEY`), example model `qwen-plus`
