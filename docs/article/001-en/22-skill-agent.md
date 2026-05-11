# Skill Agent: Wrapping Sub-Workflows into Reusable Tools

> **Tags**: `Java` `Skill` `SkillAgent` `sub-workflow` `McpAgentExecutor` `j-langchain`  
> **Prerequisite**: [MCP React Agent: The Standard Paradigm for Tool Calling](11-mcp-react-agent.md)  
> **Audience**: Java developers familiar with `McpAgentExecutor` who want to build reusable sub-workflows

---

## 1. The Problem: Agents That Keep Getting Heavier

As business complexity grows, Agent developers typically run into three pain points:

**Poor reusability**: The same sub-workflow (fetch weather + fetch flights + fetch hotels) gets reimplemented in multiple Agents. Changing one thing means changing everywhere.

**System prompt bloat**: The master Agent needs to understand the detailed workflows of all sub-tasks simultaneously, causing the `systemPrompt` to grow longer and routing to become more chaotic.

**Poor testability**: Sub-workflows embedded inside a master Agent can't run in isolation — every verification requires triggering the full pipeline.

**Skill** is designed to solve these three problems: it packages a complete sub-task workflow (systemPrompt + tool set + knowledge base) into a self-contained unit, exposes it to the master Agent as an ordinary `Tool`, and the master Agent simply passes in input and waits for output — completely unaware of the internal details.

---

## 2. Architecture

```
┌───────────────────────────────────────────────────────────┐
│                       Master Agent                        │
│                                                           │
│   McpAgentExecutor                                        │
│   ├─ LLM                                                  │
│   ├─ Tool: get_weather       ← regular tool               │
│   ├─ Tool: get_flight_price  ← regular tool               │
│   ├─ Tool: get_hotel_price   ← regular tool               │
│   └─ Tool: travel_planner    ← Skill registered as Tool   │
│                │                                          │
│                │ asTool()                                 │
│                ▼                                          │
│         ┌─────────────────────────────────┐               │
│         │            Skill                │               │
│         │  config (SKILL.md)              │               │
│         │  ownTools (explicitly registered)│              │
│         │  parentTools (allowedTools injected)│           │
│         │         │                       │               │
│         │         ▼  invoke()             │               │
│         │  McpAgentExecutor (inner executor)│             │
│         │  ├─ LLM (inherited from master) │               │
│         │  ├─ systemPrompt (SKILL.md)     │               │
│         │  ├─ ownTools                   │               │
│         │  └─ parentTools (borrowed)      │               │
│         └─────────────────────────────────┘               │
└───────────────────────────────────────────────────────────┘
```

Core design philosophy: **A Skill is a Tool externally, a full Agent internally.** The master Agent's tool-calling protocol is completely decoupled from the Skill's internal execution protocol.

---

## 3. SKILL.md: The Skill Declaration File

Skill uses a directory convention for configuration. The classpath layout looks like this:

```
skills/travel-planner/
  SKILL.md              ← frontmatter (name/description/allowed-tools) + system prompt body
  references/           ← domain knowledge injected into system prompt (optional)
    city-tips.md
  agents/               ← embedded sub-agents (optional)
    budget-advisor.md
```

`SKILL.md` uses Markdown with a YAML frontmatter:

```markdown
---
name: travel_planner
description: Travel planning skill. Queries weather, flight prices, and hotel rates
             for destinations and produces comprehensive travel advice.
             Use when the user asks about travel plans, itineraries, or city recommendations.
allowed-tools:
  - get_weather
  - get_flight_price
  - get_hotel_price
max-iterations: 15
---

# Travel Planning Workflow

You are a professional travel planning assistant, providing comprehensive travel advice.

## Phase 1: Parse Destinations
Extract all destination cities from the user's input.

## Phase 2: Collect Information
For each destination, sequentially:
1. Call get_weather to query weather conditions
2. Call get_flight_price to query flight prices
3. Call get_hotel_price to query average hotel rates

## Phase 3: Synthesize Recommendations
Consolidate the information and output weather summaries, price comparisons,
overall recommendations, and budget estimates.
```

| Field | Description |
|-------|-------------|
| `name` | Skill identifier, also the Tool name the master Agent sees |
| `description` | Routing basis for the master LLM — the more precise, the better the routing |
| `allowed-tools` | Whitelist of tool names allowed to be borrowed from the parent Agent |
| `max-iterations` | Max iterations for the inner executor (default: 10) |

---

## 4. Mode 1: Classpath SKILL.md + Master Agent (Tool Borrowing)

The most common usage: load skill config from classpath, tools are owned by the master Agent, and the Skill declares which ones it wants to borrow via `allowed-tools`.

```java
TravelTools tools = new TravelTools();
Tool weatherTool = buildTool("get_weather",      "Query city weather",    "city: String", tools::getWeather);
Tool flightTool  = buildTool("get_flight_price", "Query flight price",    "city: String", tools::getFlightPrice);
Tool hotelTool   = buildTool("get_hotel_price",  "Query hotel avg price", "city: String", tools::getHotelPrice);

var llm = ChatAliyun.builder().model("qwen-plus").temperature(0f).build();

// Load from classpath resources/skills/travel-planner/
SkillConfig config = ClasspathSkillConfigLoader.fromClasspath("skills/travel-planner");
Skill travelSkill = Skill.from(config, chainActor).llm(llm).verbose(true).build();

// Master owns all tools; Skill gets allowed-tools injected automatically at build()
McpAgentExecutor master = McpAgentExecutor.builder(chainActor)
        .llm(ChatAliyun.builder().model("qwen-plus").temperature(0f).build())
        .tools(weatherTool, flightTool, hotelTool)
        .skill(travelSkill)
        .systemPrompt("You are a travel assistant. Use the travel_planner skill for travel planning tasks.")
        .onToolCall(tc  -> System.out.println("[ToolCall] " + tc))
        .onObservation(obs -> System.out.println("[Observation] " + obs))
        .build();

String result = master.invoke("I want to travel from Shanghai to Chengdu and Xi'an. Please plan the trip.").getText();
```

**Tool borrowing mechanism**: `master.build()` iterates over registered Skills, filters the master's tool list against each Skill's `allowedTools` whitelist, and injects matching tools into the Skill's inner executor. Tools share a single instance — no duplication.

```
Master tools: [get_weather, get_flight_price, get_hotel_price]
                                ↓ allowedTools filter
Skill inner tools: [get_weather, get_flight_price, get_hotel_price]  ← all whitelisted
```

Execution logs (with `verbose(true)` enabled):

```
[skill:travel_planner] ToolCall: get_weather {"city":"Chengdu"}
[skill:travel_planner] Observation: Chengdu: Cloudy, 18~26°C, light rain in the afternoon
[skill:travel_planner] ToolCall: get_flight_price {"city":"Chengdu"}
[skill:travel_planner] Observation: Shanghai→Chengdu: ¥980 (Economy)
...

========== Travel Plan ==========
**Chengdu**
- Weather: Cloudy, 18~26°C, light rain expected — bring an umbrella
- Flight: Shanghai→Chengdu ¥980 (Economy)
- Hotel: 3-star ¥280/night, 4-star ¥520/night
- 3-night budget: ¥980 + ¥840 (3-star) = approx. ¥1,820+
...
```

---

## 5. Mode 2: Constructing SkillConfig in Code (No File Dependency)

When you want to avoid classpath files — or quickly build a Skill in unit tests — you can assemble a `SkillConfig` directly in code:

```java
SkillConfig config = SkillConfig.builder()
        .name("weather_flight_query")
        .description("Query weather and flight info for a destination")
        .allowedTools(List.of("get_weather", "get_flight_price"))
        .systemPrompt("""
                You are a travel info assistant.
                Given a city name, call get_weather then get_flight_price in sequence,
                and output a concise travel reference.
                """)
        .build();

var llm = ChatAliyun.builder().model("qwen-plus").temperature(0f).build();
Skill skill = Skill.from(config, chainActor).llm(llm).build();

McpAgentExecutor master = McpAgentExecutor.builder(chainActor)
        .llm(ChatAliyun.builder().model("qwen-plus").temperature(0f).build())
        .tools(weatherTool, flightTool)
        .skill(skill)
        .build();

String result = master.invoke("I'm planning a trip to Guilin. Check the weather and flights.").getText();
```

`SkillConfig` is completely storage-agnostic — the same configuration structure can be loaded from classpath, read from a database, or constructed in code as shown here. All three sources are fully transparent to `Skill.from()`.

---

## 6. Mode 3: Skill Standalone (Without a Master Agent)

A Skill doesn't have to be attached to a master Agent. If the sub-task workflow is self-contained, you can call `skill.invoke()` directly:

```java
SkillConfig config = ClasspathSkillConfigLoader.fromClasspath("skills/travel-planner");
Skill skill = Skill.from(config, chainActor)
        .llm(ChatAliyun.builder().model("qwen-plus").temperature(0f).build())
        .tools(weatherTool, flightTool, hotelTool)  // registered directly; no allowedTools needed
        .onToolCall(tc  -> System.out.println("[ToolCall]    " + tc))
        .onObservation(obs -> System.out.println("[Observation] " + obs))
        .build();

String result = skill.invoke("I want to visit Sanya. Departing from Shanghai.");
System.out.println(result);
```

In standalone mode, tools are registered via `.tools()` directly — no borrowing flow. Common use cases:
- Batch scripting (no need for a master Agent to dispatch tasks)
- Integration tests (verify the Skill's workflow logic in isolation)
- Microservice entry points (the Skill itself is the service boundary)

---

## 7. Choosing a Mode

| Scenario | Recommended Mode | Reason |
|----------|-----------------|--------|
| Master Agent routes to multiple sub-tasks | classpath + Master | Master owns tools centrally and allocates them as needed |
| Quick prototype / unit test | Code-constructed SkillConfig | No file dependency; config is code |
| Self-contained sub-task, no routing needed | Skill standalone | Simplest path — direct `skill.invoke()` |

---

## 8. Prerequisites

1. **`ALIYUN_KEY`** environment variable — examples use `qwen-plus`
2. Classpath mode requires `skills/travel-planner/SKILL.md` under `src/test/resources/`
3. Code-constructed and standalone modes have no file dependencies

---

## 9. Summary

Skill addresses the core problem of **sub-workflow encapsulation and reuse**:

- **Uniform external interface**: Skill is exposed to the master Agent as a plain `Tool` — the master sees no internal details
- **Layered tool sourcing**: script tools (`scripts/`), explicitly registered tools (`.tools()`), and borrowed tools (`allowedTools`) merge cleanly without interfering with each other
- **Config/implementation decoupling**: `SkillConfig` is storage-agnostic — classpath, database, and code-constructed configs are equivalent
- **Three runtime modes**: classpath + Master (production default), code-constructed (test-friendly), standalone (complete sub-task) — pick what fits

A well-designed Skill is like a microservice with a stable interface — the master Agent only needs to know *what* to call; the Skill handles *how* to do it.

---

> 📎 Resources
> - Full source: [Article22SkillAgent.java](../../../src/test/java/org/salt/jlangchain/demo/article/Article22SkillAgent.java)
> - Skill config: [skills/travel-planner/SKILL.md](../../../src/test/resources/skills/travel-planner/SKILL.md)
> - j-langchain GitHub: https://github.com/flower-trees/j-langchain
> - Runtime: `ALIYUN_KEY` (`qwen-plus`)
