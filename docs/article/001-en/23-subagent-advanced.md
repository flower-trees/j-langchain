# SubAgent Advanced: LLM Strategies, Tool Borrowing, and Skill Nesting

> **Tags**: `Java` `SubAgent` `LLM strategy` `llmFactory` `allowedTools` `Skill nesting` `j-langchain`  
> **Prerequisite**: [SubAgent Basics: An Autonomous Agent with Its Own Tools](23-subagent-basic.md)  
> **Audience**: Java developers who know SubAgent basics and want fine-grained control over model selection, tool permissions, and multi-layer nesting

---

## 1. Four Advanced Scenarios

The previous article covered three basic SubAgent patterns (standalone, registered with master, code-constructed). This article focuses on four advanced scenarios:

| Scenario | Problem it solves |
|----------|-------------------|
| `model=inherit` | SubAgent reuses the master Agent's LLM — no redundant config |
| `llmFactory` | SubAgent declares a model name in its config file; the master builds LLM instances by name at runtime |
| `allowedTools` | SubAgent borrows a subset of the master's tools under the principle of least privilege |
| Skill embedding SubAgent | A Skill's `agents/` directory holds lightweight SubAgents, forming two-level nesting |

---

## 2. The 3-Tier LLM Resolution Chain

Before diving into these scenarios, it helps to understand how SubAgent resolves its LLM. On each `invoke()`, SubAgent determines which LLM to use with the following priority:

```
Priority  Scenario                    Configuration
──────────────────────────────────────────────────────────────
1         Explicit injection          SubAgent.Builder.llm(llm)
2         Inherit from master         AGENT.md: model: inherit
                                      master build() auto-calls injectLlm(masterLlm)
3         Factory by model name       AGENT.md: model: qwen-plus
                                      master build() calls injectLlmFactory(factory)
                                      factory.apply("qwen-plus") runs before first invoke()
```

These three priorities cover all real deployment scenarios: **explicit injection for tests**, **inherit for shared LLM**, **factory for differentiated model management**.

---

## 3. model=inherit: SubAgent Inherits the Master's LLM

The simplest LLM reuse pattern — the SubAgent doesn't configure its own LLM and directly uses the master's.

**Config file** (`agents/weather-checker/AGENT.md`):

```markdown
---
name: weather_checker
description: Weather specialist. Queries current weather for a city. Use when weather info is needed.
model: inherit
tools:
  - get_weather
max-iterations: 5
---

You are a weather specialist, focused on querying current city weather conditions.
Given a city name, call the get_weather tool and return a concise weather report.
```

`model: inherit` plus `tools: [get_weather]` (borrows the parent Agent's `get_weather` tool).

**Code**:

```java
var masterLlm = ChatAliyun.builder().model("qwen-plus").temperature(0f).build();

SubAgentConfig config = ClasspathSubAgentConfigLoader.fromClasspath("agents/weather-checker");
// model=inherit → don't call .llm() in builder; master build() auto-injects
SubAgent weatherAgent = SubAgent.from(config, chainActor)
        .tools(buildTool("get_weather", "Query city weather", "city: String", tools::getWeather))
        .onToolCall(tc  -> System.out.println("[weather_checker] ToolCall: " + tc))
        .onObservation(obs -> System.out.println("[weather_checker] Observation: " + obs))
        .build();

McpAgentExecutor master = McpAgentExecutor.builder(chainActor)
        .llm(masterLlm)          // auto-injected into model=inherit SubAgents
        .subAgent(weatherAgent)
        .systemPrompt("You are a travel assistant. Use weather_checker for weather queries.")
        .build();

String result = master.invoke("What's the weather like in Chengdu right now?").getText();
```

Master's `build()` logic internally:

```java
for (SubAgent subAgent : subAgents) {
    if (subAgent.isInheritModel()) {
        subAgent.injectLlm(masterLlm);  // inject master's LLM instance directly
    }
    ...
}
```

**When to use**: Sub-tasks are similar in nature to the main task and don't need differentiated models — reduces config duplication.

---

## 4. llmFactory: Build LLM Dynamically by Model Name

When different SubAgents need different models, `llmFactory` provides a centralized LLM management entry point: SubAgents declare a model name in config, and the master Agent provides a "build-by-name" factory function.

```java
// SubAgent config: declare model name, no LLM instance
SubAgentConfig config = SubAgentConfig.builder()
        .name("flight_checker")
        .description("Flight price specialist. Use when flight info is needed.")
        .model("qwen-turbo")    // resolved by master's llmFactory
        .systemPrompt("You are a flight specialist. Given a city name, call get_flight_price.")
        .build();

SubAgent flightAgent = SubAgent.from(config, chainActor)
        // no .llm() call — resolved by llmFactory at master build()
        .tools(buildTool("get_flight_price", "Query flight price", "city: String", tools::getFlightPrice))
        .build();

McpAgentExecutor master = McpAgentExecutor.builder(chainActor)
        .llm(ChatAliyun.builder().model("qwen-plus").temperature(0f).build())
        .llmFactory(modelName ->
            ChatAliyun.builder().model(modelName).temperature(0f).build()
        )
        .subAgent(flightAgent)
        .systemPrompt("You are a travel assistant. Use flight_checker for flight queries.")
        .build();

String result = master.invoke("Check flights from Shanghai to Xi'an.").getText();
```

Master's `build()` injects the factory:

```java
} else if (llmFactory != null) {
    subAgent.injectLlmFactory(llmFactory); // SubAgent builds LLM by model name
}
```

`resolveLlm()` executes `factory.apply("qwen-turbo")` on the first `invoke()` — the LLM instance is created lazily.

**The value of llmFactory**:

- `llmFactory` is a centralized LLM registry entry — adding a new model only changes the factory; all SubAgents benefit
- The `model` field in `SubAgentConfig` is a plain string that can come from a config file or database — no code changes needed at deployment

```
Master llmFactory: modelName → ChatAliyun(modelName)
                        ↓
flight_checker (model=qwen-turbo)  → ChatAliyun("qwen-turbo")
analyst        (model=qwen-max)    → ChatAliyun("qwen-max")
summarizer     (model=qwen-turbo)  → ChatAliyun("qwen-turbo")
```

---

## 5. allowedTools: Borrow a Subset of Parent Tools

A SubAgent doesn't need to own all its tools. When tools are already registered on the master Agent, the SubAgent can declare an `allowedTools` whitelist, and the master automatically filters and injects only the matching tools at `build()`.

```java
Tool weatherTool = buildTool("get_weather",      "Query city weather",    "city: String", tools::getWeather);
Tool flightTool  = buildTool("get_flight_price", "Query flight price",    "city: String", tools::getFlightPrice);
Tool hotelTool   = buildTool("get_hotel_price",  "Query hotel avg price", "city: String", tools::getHotelPrice);

// SubAgent declares it only wants get_weather and get_hotel_price, not get_flight_price
SubAgentConfig config = SubAgentConfig.builder()
        .name("accommodation_advisor")
        .description("Accommodation advisor. Queries weather and hotel prices. Use for accommodation advice.")
        .allowedTools(List.of("get_weather", "get_hotel_price"))
        .systemPrompt("""
                You are an accommodation advisor.
                Given a city name, call get_weather then get_hotel_price,
                and provide accommodation recommendations based on weather and pricing.
                """)
        .build();

// SubAgent has no ownTools — everything comes from allowedTools injection
SubAgent advisor = SubAgent.from(config, chainActor)
        .llm(llm)
        .onToolCall(tc  -> System.out.println("[accommodation_advisor] ToolCall: " + tc))
        .onObservation(obs -> System.out.println("[accommodation_advisor] Observation: " + obs))
        .build();

// Master owns all 3 tools; SubAgent only gets the 2 it declared
McpAgentExecutor master = McpAgentExecutor.builder(chainActor)
        .llm(ChatAliyun.builder().model("qwen-plus").temperature(0f).build())
        .tools(weatherTool, flightTool, hotelTool)
        .subAgent(advisor)
        .systemPrompt("You are a travel assistant. Use accommodation_advisor for accommodation advice.")
        .build();

String result = master.invoke("I'm going to Guilin. Where should I stay?").getText();
```

Injection flow:

```
Master tools: [get_weather, get_flight_price, get_hotel_price]
                       ↓ allowedTools whitelist filter
SubAgent inner tools: [get_weather, get_hotel_price]   ← get_flight_price blocked
```

**Principle of least privilege**: SubAgent only sees the tools it explicitly declared it needs. Even if the master has 50 tools, the SubAgent only receives the whitelisted ones — preventing accidental calls to unrelated tools and reducing noise in the LLM's tool selection.

Execution:

```
[accommodation_advisor] ToolCall: get_weather {"city":"Guilin"}
[accommodation_advisor] Observation: Guilin: Rainy showers, 23~30°C
[accommodation_advisor] ToolCall: get_hotel_price {"city":"Guilin"}
[accommodation_advisor] Observation: Guilin: 3-star ¥200/night, 4-star ¥380/night

Accommodation advice:
Guilin has frequent rain lately. The 4-star hotel (¥380/night) in the city center
is recommended for better connectivity. Budget-conscious travelers can opt for
3-star (¥200/night).
3-night budget: ¥600 (3-star) ~ ¥1,140 (4-star)
```

---

## 6. Skill Embedding SubAgent: Two-Level Nesting

A Skill's `agents/` subdirectory can hold lightweight SubAgent configurations. These SubAgents are automatically registered as tools in the Skill's inner executor, forming a "Master Agent → Skill → SubAgent" two-level nesting.

**Directory layout**:

```
skills/travel-planner/
  SKILL.md
  agents/
    budget-advisor.md     ← embedded SubAgent, scoped to Skill's own tool set
```

**`budget-advisor.md`**:

```markdown
---
name: budget_advisor
description: Travel budget advisor. Queries hotel rates for a city and estimates 3-night accommodation costs.
             Use when estimating travel budget.
allowed-tools:
  - get_hotel_price
---

You are a travel budget advisor, specialized in accommodation cost estimation.
Given a city name:
1. Call get_hotel_price to query average hotel rates
2. Calculate the total cost for 3 nights (both 3-star and 4-star tiers)
3. Output a concise budget recommendation
```

The embedded SubAgent's `allowed-tools` references tools from **the Skill's own tool set** (not the master Agent's tools) — borrowing scope is strictly bounded within the Skill.

**Code**:

```java
SkillConfig config = ClasspathSkillConfigLoader.fromClasspath("skills/travel-planner");
System.out.println("Embedded agents: " + config.getAgents().size());
config.getAgents().forEach(a ->
    System.out.println("  - " + a.getName() + " allowedTools=" + a.getAllowedTools()));

Skill travelSkill = Skill.from(config, chainActor)
        .llm(llm)
        .tools(weatherTool, flightTool, hotelTool)
        .verbose(true)
        .build();

McpAgentExecutor master = McpAgentExecutor.builder(chainActor)
        .llm(ChatAliyun.builder().model("qwen-plus").temperature(0f).build())
        .skill(travelSkill)
        .systemPrompt("You are a travel assistant. Use travel_planner skill for travel planning tasks.")
        .build();

String result = master.invoke("I want to visit Sanya for 3 nights. Estimate my accommodation budget.").getText();
```

Execution logs (two-level prefix with `verbose(true)`):

```
Embedded agents: 1
  - budget_advisor allowedTools=[get_hotel_price]

[skill:travel_planner] ToolCall: budget_advisor {"input":"Sanya accommodation budget, 3 nights"}
[skill:travel_planner>budget_advisor] ToolCall: get_hotel_price {"city":"Sanya"}
[skill:travel_planner>budget_advisor] Observation: Sanya: 3-star ¥480/night, 4-star ¥950/night

========== Skill + Embedded SubAgent Result ==========
Sanya 3-night accommodation budget:
- 3-star hotel: ¥480 × 3 = ¥1,440
- 4-star hotel: ¥950 × 3 = ¥2,850
Tip: Sanya hotels fill up fast in peak season — book 4-star early...
```

The two-level log prefix `[skill:travel_planner>budget_advisor]` clearly shows the call chain hierarchy, making multi-level nesting easy to trace.

---

## 7. Summary of Advanced Scenarios

| Scenario | Key API | Typical use case |
|----------|---------|-----------------|
| `model=inherit` | `AGENT.md: model: inherit` | Sub-task uses same model as master — reduce config redundancy |
| `llmFactory` | `.llmFactory(name -> ...)` | Multiple SubAgents with different models — centralized factory management |
| `allowedTools` | `SubAgentConfig.allowedTools(...)` | SubAgent borrows parent tools with least-privilege isolation |
| Skill embedding SubAgent | `agents/*.md` directory | Skill further decomposes sub-tasks internally |

---

## 8. Prerequisites

1. **`ALIYUN_KEY`** environment variable — examples use `qwen-plus` / `qwen-turbo`
2. `model=inherit` and embedded SubAgent modes require the corresponding `AGENT.md` files
3. `allowedTools` and `llmFactory` modes can use code-constructed `SubAgentConfig` — no file dependencies

---

## 9. Summary

SubAgent's advanced capabilities make multi-agent systems more flexible in model selection and tool permissions:

- **3-tier LLM resolution chain** covers all deployment scenarios: explicit injection for tests, `inherit` for LLM reuse, `llmFactory` for differentiated model management
- **`allowedTools` whitelist** enforces least privilege — SubAgent only sees the tools it declared it needs; the LLM won't accidentally call unrelated tools
- **Skill embedding SubAgent** naturally expresses "Skill delegates complex sub-tasks to specialized mini-agents," while `verbose` two-level log prefixes keep the call chain transparent and traceable

---

> 📎 Resources
> - Full source: [Article23SubAgent.java](../../../src/test/java/org/salt/jlangchain/demo/article/Article23SubAgent.java) — methods `testInheritModel` / `testLlmFactory` / `testAllowedToolsFromParent` / `testSkillWithEmbeddedAgent`
> - Embedded sub-agent config: [skills/travel-planner/agents/budget-advisor.md](../../../src/test/resources/skills/travel-planner/agents/budget-advisor.md)
> - Weather agent config: [agents/weather-checker/AGENT.md](../../../src/test/resources/agents/weather-checker/AGENT.md)
> - j-langchain GitHub: https://github.com/flower-trees/j-langchain
> - Runtime: `ALIYUN_KEY` (`qwen-plus` / `qwen-turbo`)
