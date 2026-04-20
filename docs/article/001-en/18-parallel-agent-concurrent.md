# Parallel Agent Research: Fan-out / Fan-in Travel Planner with `concurrent`

> **Tags**: Java, Agent, ReAct, j-langchain, Multi-Agent, Parallel, concurrent, Travel, AgentExecutor  
> **Prerequisite**: [Dual-Agent Pipelines: Analysis Agent + Execution Agent for Ticket Handling](16-multi-agent-executor.md)

---

## 1. When Sequential Agents Are Not Enough

The previous article chained two agents sequentially because the execution agent needed the analysis agent's output. But many real workflows have **no data dependency** between sub-tasks—they just need the same starting input.

Consider travel planning: given "Kyoto, 5 days, April," the system must research three independent dimensions:

- **Attractions**: which sights to visit, what to eat
- **Weather**: April climate, travel tips
- **Budget**: flights, hotels, daily spend

None of these depends on the others. Running them sequentially wastes time:

| Mode | When to use | Total time |
|------|------------|------------|
| Sequential (`next → next`) | B needs A's result | T_A + T_B + T_C |
| Parallel (`concurrent`) | A, B, C share the same input and are independent | max(T_A, T_B, T_C) |

---

## 2. Architecture

```
User request (Kyoto, 5 days, April)
     ↓
TranslateHandler (format task brief)
     ↓
┌──────────────────────────────────────┐
│  concurrent node                      │
│  Attraction Agent → attraction report │
│  Weather Agent    → weather report    │
│  Budget Agent     → budget report    │
└──────────────────────────────────────┘
     ↓ Map<alias, ChatGeneration>
merge lambda (combine reports → synthesis prompt)
     ↓
Synthesis Agent (generate complete itinerary)
     ↓
TranslateHandler (format final output)
     ↓
Travel plan
```

Three specialist agents fan out in parallel; their results are merged and handed off to a synthesis agent. This **Fan-out / Fan-in** topology is the canonical parallel agent pattern.

---

## 3. Tool Definitions

Each agent gets its own isolated tool set—no overlap.

**Attraction tools** (`AttractionTools`): `getTopAttractions(city)`, `getLocalCuisine(city)`

**Weather tools** (`WeatherTools`): `getWeatherByMonth(city, month)`, `getTravelAdvice(city, month)`

**Budget tools** (`BudgetTools`): `getFlightCost(destination, month)`, `getHotelCost(city, nights)`, `getDailyExpense(city)`

**Plan tools** (`PlanTools`): `getPlanTimestamp()` — stamps the generated plan with the current time.

All methods are annotated with `@AgentTool` and `@Param`, matching the same pattern used in Article 16.

---

## 4. Building the Parallel Pipeline

```java
// Three specialist agents — each with its own tools and LLM instance
AgentExecutor attractionAgent = AgentExecutor.builder(chainActor)
    .llm(ChatAliyun.builder().model("qwen-plus").temperature(0f).build())
    .tools(new AttractionTools())
    .maxIterations(6)
    .onThought(t -> System.out.println("[Attraction] " + t))
    .onObservation(obs -> System.out.println("[Attraction result] " + obs))
    .build();

AgentExecutor weatherAgent = AgentExecutor.builder(chainActor)
    .llm(ChatAliyun.builder().model("qwen-plus").temperature(0f).build())
    .tools(new WeatherTools())
    .maxIterations(6)
    .build();

AgentExecutor budgetAgent = AgentExecutor.builder(chainActor)
    .llm(ChatAliyun.builder().model("qwen-plus").temperature(0f).build())
    .tools(new BudgetTools())
    .maxIterations(8)
    .build();

// Synthesis agent — combines the three reports
AgentExecutor synthesisAgent = AgentExecutor.builder(chainActor)
    .llm(ChatAliyun.builder().model("qwen-plus").temperature(0.7f).build())
    .tools(new PlanTools())
    .maxIterations(3)
    .build();

FlowInstance travelPlan = chainActor.builder()

    // Node 1: format user input into a shared task brief
    .next(new TranslateHandler<>(userInput ->
        "Travel request: " + userInput +
        "\nPlease analyse this destination from your specialist perspective."
    ))

    // Node 2: three agents run in parallel
    .concurrent(
        600000, // timeout: 10 minutes
        Info.c(attractionAgent).cAlias("attraction"),
        Info.c(weatherAgent).cAlias("weather"),
        Info.c(budgetAgent).cAlias("budget")
    )

    // Node 3: merge results and build the synthesis prompt
    .next(map -> {
        Map<String, Object> results = (Map<String, Object>) map;
        String attractions = ((ChatGeneration) results.get("attraction")).getText();
        String weather     = ((ChatGeneration) results.get("weather")).getText();
        String budget      = ((ChatGeneration) results.get("budget")).getText();

        return "Please get the current timestamp, then produce a complete travel plan " +
            "based on the following three specialist reports:\n\n" +
            "=== Attractions & Experiences ===\n" + attractions + "\n\n" +
            "=== Weather & Tips ===\n" + weather + "\n\n" +
            "=== Cost & Budget ===\n" + budget + "\n\n" +
            "Output: 1) daily itinerary; 2) budget summary; 3) travel tips.";
    })

    // Node 4: synthesis agent generates the complete plan
    .next(synthesisAgent)

    // Node 5: format final output
    .next(new TranslateHandler<>(output -> {
        String plan = ((ChatGeneration) output).getText();
        return "\n===== Travel Plan =====\n" + plan + "\n=======================";
    }))

    .build();

String result = chainActor.invoke(travelPlan, Map.of(
    "input", "Kyoto, 5 days 4 nights, departing April, moderate budget"
));
System.out.println(result);
```

---

## 5. Execution Trace

```
===== Travel planning request received =====
Kyoto, 5 days 4 nights, departing April, moderate budget

--- Launching three parallel research agents ---

[Attraction] Thought: I need to look up Kyoto's top attractions.
Action: get_top_attractions  {"city": "Kyoto"}
[Attraction result] Kinkaku-ji, Arashiyama bamboo grove, Fushimi Inari...

[Weather] Thought: I need April weather for Kyoto.
Action: get_weather_by_month  {"city": "Kyoto", "month": "April"}
[Weather result] Cherry-blossom season, 10–20°C, mostly sunny, extremely crowded...

[Budget] Thought: I need flight, hotel, and daily spend data.
Action: get_flight_cost  {"destination": "Kyoto", "month": "April"}
[Budget result] Shanghai → Osaka KIX, cherry-blossom season: ¥2800–4500 round-trip...

... (agents continue their ReAct loops in parallel) ...

[Attraction] Final Answer: Top Kyoto sights include Kinkaku-ji, Arashiyama...
[Weather]    Final Answer: April is peak cherry-blossom season. Temperatures 10–20°C...
[Budget]     Final Answer: 5-day budget estimate: flights ¥3500, hotel ¥2400, daily ¥2000...

--- Three reports ready — handing off to synthesis agent ---

[Synthesis] Action: get_plan_timestamp
[Synthesis result] Plan generated: 2025-04-15 14:32

[Synthesis] Final Answer:
Plan generated: 2025-04-15 14:32

=== 5-Day Kyoto Cherry-Blossom Itinerary ===

Day 1: Arrive Osaka → JR to Kyoto → Arashiyama bamboo grove → Tenryu-ji
Day 2: Kinkaku-ji → Ninna-ji → Ryoan-ji → Philosopher's Path → Nanzen-ji
Day 3: Fushimi Inari (arrive before 6 AM) → Nishiki Market → Pontocho dinner
Day 4: Kiyomizu-dera → Ninenzaka → Gion Hanamikoji → Yasaka Shrine
Day 5: Free morning → afternoon transfer to Osaka KIX

Budget summary:
  Round-trip flights:  ¥3,500 (book 2 months early)
  Accommodation 4 nts: ¥2,400 (mid-range ~¥600/night)
  Daily spend × 5:     ¥2,000
  Total per person:    ~¥7,900

Tips:
  · Book accommodation 3 months in advance — cherry-blossom prices double
  · Get an IC card (Suica/ICOCA) for cheaper bus travel
  · Visit Fushimi Inari before dawn to avoid the crowds
```

---

## 6. Two Design Rules for Parallel Pipelines

### Rule 1 — Always set `cAlias`

`concurrent` returns `Map<String, Object>`. Without an alias the key is the agent's internal node ID (a UUID fragment), making the merge step fragile. Use `Info.c(agent).cAlias("key")` to assign a meaningful name:

```java
// Without alias — key is an opaque ID, unreliable
.concurrent(attractionAgent, weatherAgent, budgetAgent)

// With alias — key is explicit and stable
.concurrent(
    Info.c(attractionAgent).cAlias("attraction"),
    Info.c(weatherAgent).cAlias("weather"),
    Info.c(budgetAgent).cAlias("budget")
)
```

### Rule 2 — The merge node has three jobs

The lambda between `concurrent` and the synthesis agent must:

1. **Extract** — pull each `ChatGeneration` from the map and call `.getText()`
2. **Assemble** — concatenate the reports with clear section headings so the synthesis agent can tell them apart
3. **Specify the task** — tell the synthesis agent exactly what to output (daily itinerary, budget table, tips); don't leave it guessing

Skipping any of these three degrades synthesis quality.

---

## 7. Timeout Control

The default `concurrent` timeout is 3 000 ms—far too short for LLM calls. Set an explicit timeout (milliseconds) as the first argument:

```java
.concurrent(
    600000L,  // 10-minute ceiling
    Info.c(attractionAgent).cAlias("attraction"),
    Info.c(weatherAgent).cAlias("weather"),
    Info.c(budgetAgent).cAlias("budget")
)
```

If an agent times out its key is absent from the result map. Guard against this in the merge lambda:

```java
String attractions = results.containsKey("attraction")
    ? ((ChatGeneration) results.get("attraction")).getText()
    : "(attraction data unavailable — timed out)";
```

---

## 8. Choosing Between Sequential and Parallel

| Dimension | Sequential (`next → next`) | Parallel (`concurrent`) |
|-----------|---------------------------|------------------------|
| Prerequisite | Next step needs the previous step's output | All steps share the same upstream input, no inter-dependency |
| Typical examples | Analyse then execute; approve then notify | Multi-dimensional research, multi-model voting, parallel scoring |
| Total time | T_A + T_B + T_C | max(T_A, T_B, T_C) |
| Tool design | Tools across agents may reference each other | Tool sets are completely isolated per agent |
| Merge node | `TranslateHandler` for format conversion | Map extraction + prompt assembly |

For more complex topologies—parallel collection, then sequential decision, then parallel notification—combine `concurrent`, `next`, and `notify` nodes in a single `chainActor.builder()` chain.

---

## 9. Summary

- `concurrent()` runs multiple `AgentExecutor` instances simultaneously using a `CountDownLatch` under the hood.
- `Info.c(agent).cAlias("key")` names each parallel agent's result in the output map.
- The merge lambda (`next(map -> {...})`) extracts, assembles, and tasks the synthesis agent.
- Set an explicit timeout in `concurrent(timeoutMs, ...)` for LLM-based workloads.
- **Sequential solves dependency; parallel solves independent collection.**

---

> Sample: `Article18ParallelTravelResearch.java` (`parallelTravelResearch`) — requires `ALIYUN_KEY` (`qwen-plus`).
