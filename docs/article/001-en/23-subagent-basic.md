# SubAgent Basics: An Autonomous Agent with Its Own Tools

> **Tags**: `Java` `SubAgent` `autonomous agent` `own tools` `McpAgentExecutor` `j-langchain`  
> **Prerequisite**: [Skill Agent: Wrapping Sub-Workflows into Reusable Tools](22-skill-agent.md)  
> **Audience**: Java developers who know Skill and want to build independent sub-agents with dedicated tool sets

---

## 1. The Limit of Skill: Why SubAgent?

The previous article showed how Skill encapsulates sub-workflows — but Skill has one constraint: **a Skill does not own tools; it can only borrow them from its parent Agent.** This means that if a sub-task needs dedicated tools (e.g., a specific API client or private database connection), those tools must be registered on the master Agent for the Skill to borrow by name.

When a sub-task needs more independence, that borrowing relationship becomes awkward:

- The sub-task has proprietary tools that shouldn't be exposed to the master Agent
- The sub-task needs a different model (performance vs. cost tradeoff)
- Multiple sub-tasks have non-overlapping tool sets — "borrowing" only adds coupling

**SubAgent** is designed for exactly these scenarios: it owns its own tools, can configure its LLM independently, and still exposes itself to the master Agent as a plain `Tool`.

---

## 2. Skill vs. SubAgent: Core Differences

| Dimension | Skill | SubAgent |
|-----------|-------|----------|
| Tool source | Borrow-only from parent Agent | Own tools (optional parent borrowing) |
| LLM | Inherits master Agent's LLM | Inherit / specify model name / explicitly inject |
| Knowledge injection | `references/` docs appended to system prompt | Associated Skill knowledge appended to system prompt |
| Config file | `SKILL.md` | `AGENT.md` |
| Internal composability | Can embed SubAgent (`agents/`) | Can call Skills (as Tools) |
| Typical use case | Standardized sub-flows, borrowing parent tools | Specialized domain agents, dedicated tools |

One-line summary: Skill is "borrow tools from the parent and get the job done," SubAgent is "bring your own tools and get the job done."

---

## 3. Architecture

```
┌───────────────────────────────────────────────────────────┐
│                       Master Agent                        │
│                                                           │
│   McpAgentExecutor                                        │
│   ├─ LLM (master LLM)                                     │
│   └─ Tool: travel_researcher  ← SubAgent as plain Tool    │
│                │                                          │
│                │ asTool()                                 │
│                ▼                                          │
│   ┌──────────────────────────────────────────────┐        │
│   │                  SubAgent                    │        │
│   │  config (AGENT.md)                           │        │
│   │  ownTools (explicitly registered)            │        │
│   │  inheritedTools (allowedTools injected)       │        │
│   │         │                                    │        │
│   │         ▼  invoke()                          │        │
│   │  McpAgentExecutor (inner executor)           │        │
│   │  ├─ LLM (resolved via 3-tier chain)          │        │
│   │  ├─ systemPrompt (AGENT.md body)             │        │
│   │  ├─ ownTools                                │        │
│   │  └─ inheritedTools (borrowed)                │        │
│   └──────────────────────────────────────────────┘        │
└───────────────────────────────────────────────────────────┘
```

---

## 4. AGENT.md: The Sub-Agent Declaration File

`AGENT.md` uses the same format as `SKILL.md` — Markdown with a YAML frontmatter:

```
agents/travel-researcher/
  AGENT.md     ← frontmatter + system prompt body
```

```markdown
---
name: travel_researcher
description: Travel information research expert. Queries weather, flights, and hotels
             for destinations and outputs comprehensive travel advice.
             Use when users need consolidated travel information for a destination.
skills:
  - skills/travel-planner
max-iterations: 15
---

You are a travel information research expert with professional travel planning knowledge.

Your responsibilities:
1. Understand the user's travel needs and extract destination cities
2. Use available tools to query weather, flights, and hotel information
3. Synthesize the results using your travel planning knowledge and output comprehensive travel advice
```

| Field | Description |
|-------|-------------|
| `name` | SubAgent identifier, also the Tool name |
| `description` | Tool description seen by the master LLM — determines routing accuracy |
| `model` | `inherit` (use master's LLM) / model name (e.g., `qwen-plus`) / empty (explicit injection) |
| `tools` | Whitelist of tool names allowed to be borrowed from the parent Agent |
| `skills` | Skill directory paths for knowledge injection (not tool invocation) |
| `max-iterations` | Max iterations for the inner executor |

> **Note**: The `skills` field in `AGENT.md` is **knowledge injection**, not tool invocation. The Skill's `systemPrompt` and `references` content is appended to the SubAgent's system prompt — the SubAgent "knows" those guidelines but does not run the Skill's inner executor.

---

## 5. Usage 1: SubAgent Standalone

Like a Skill, a SubAgent can run without a master Agent — useful when the sub-task is a complete business unit on its own:

```java
TravelTools tools = new TravelTools();

SubAgentConfig config = ClasspathSubAgentConfigLoader.fromClasspath("agents/travel-researcher");
SubAgent researcher = SubAgent.from(config, chainActor)
        .llm(ChatAliyun.builder().model("qwen-plus").temperature(0f).build())
        .tools(
            buildTool("get_weather",      "Query city weather",    "city: String", tools::getWeather),
            buildTool("get_flight_price", "Query flight price",    "city: String", tools::getFlightPrice),
            buildTool("get_hotel_price",  "Query hotel avg price", "city: String", tools::getHotelPrice)
        )
        .onToolCall(tc  -> System.out.println("[ToolCall]    " + tc))
        .onObservation(obs -> System.out.println("[Observation] " + obs))
        .build();

String result = researcher.invoke("I want to visit Sanya. Departing from Shanghai.");
System.out.println(result);
```

Execution:

```
[ToolCall]    get_weather {"city":"Sanya"}
[Observation] Sanya: Sunny, 28~33°C, high UV index
[ToolCall]    get_flight_price {"city":"Sanya"}
[Observation] Shanghai→Sanya: ¥1,600 (includes 15kg baggage)
[ToolCall]    get_hotel_price {"city":"Sanya"}
[Observation] Sanya: 3-star ¥480/night, 4-star ¥950/night

========== SubAgent Standalone Result ==========
**Sanya Travel Advice**

Weather: Sunny, 28~33°C, strong UV — bring sunscreen

Flight: Shanghai→Sanya ¥1,600 (15kg baggage included)

Accommodation (3 nights):
- 3-star hotel: ¥480 × 3 = ¥1,440
- 4-star hotel: ¥950 × 3 = ¥2,850

Total budget: ¥3,040 (3-star) ~ ¥4,450 (4-star)
```

---

## 6. Usage 2: AGENT.md + Registered with Master Agent

The more common pattern — attach the SubAgent to a master Agent and let the master handle task routing:

```java
var llm = ChatAliyun.builder().model("qwen-plus").temperature(0f).build();

SubAgentConfig config = ClasspathSubAgentConfigLoader.fromClasspath("agents/travel-researcher");
SubAgent researcher = SubAgent.from(config, chainActor)
        .llm(llm)
        .tools(
            buildTool("get_weather",      "Query city weather",    "city: String", tools::getWeather),
            buildTool("get_flight_price", "Query flight price",    "city: String", tools::getFlightPrice),
            buildTool("get_hotel_price",  "Query hotel avg price", "city: String", tools::getHotelPrice)
        )
        .verbose(true)
        .build();

McpAgentExecutor master = McpAgentExecutor.builder(chainActor)
        .llm(ChatAliyun.builder().model("qwen-plus").temperature(0f).build())
        .subAgent(researcher)
        .systemPrompt("You are a travel assistant. Use travel_researcher for travel info queries.")
        .onToolCall(tc  -> System.out.println("[master] ToolCall: " + tc))
        .onObservation(obs -> System.out.println("[master] Observation: " + obs))
        .build();

String result = master.invoke("I'm going from Shanghai to Chengdu and Xi'an. Get me travel info.").getText();
```

The master Agent's perspective:

```
[master] ToolCall: travel_researcher {"input":"Going from Shanghai to Chengdu and Xi'an..."}
[subagent:travel_researcher] ToolCall: get_weather {"city":"Chengdu"}
[subagent:travel_researcher] Observation: Chengdu: Cloudy, 18~26°C, light rain in afternoon
...
[master] Observation: (SubAgent's consolidated travel report)
```

The master sees a single `travel_researcher` tool call. All internal tool calls inside the SubAgent are fully transparent — invisible to the master.

---

## 7. Usage 3: Constructing SubAgentConfig in Code

No `AGENT.md` file required — assemble the config in code. Ideal for tests or dynamically generated SubAgents:

```java
SubAgentConfig config = SubAgentConfig.builder()
        .name("weather_flight_agent")
        .description("Dedicated agent for querying destination weather and flights")
        .systemPrompt("""
                You are a travel info expert.
                Given a city name, call get_weather then get_flight_price in sequence,
                and output a concise travel reference.
                """)
        .build();

SubAgent agent = SubAgent.from(config, chainActor)
        .llm(ChatAliyun.builder().model("qwen-plus").temperature(0f).build())
        .tools(
            buildTool("get_weather",      "Query city weather", "city: String", tools::getWeather),
            buildTool("get_flight_price", "Query flight price", "city: String", tools::getFlightPrice)
        )
        .onToolCall(tc  -> System.out.println("[ToolCall]    " + tc))
        .onObservation(obs -> System.out.println("[Observation] " + obs))
        .build();

String result = agent.invoke("I'm planning a trip to Guilin. Check weather and flights.");
System.out.println(result);
```

Just like `SkillConfig`, `SubAgentConfig` is storage-agnostic: classpath loading, database reads, and code construction are all equivalent to `SubAgent.from()`.

---

## 8. Choosing a Usage Pattern

| Scenario | Recommended pattern | Reason |
|----------|--------------------|-|
| Production, multiple sub-agents collaborating | AGENT.md + Master | Config maintained independently; master handles routing |
| Fully self-contained sub-task | SubAgent standalone | Simplest path — no master needed |
| Testing / rapid prototype | Code-constructed config | No file dependency; config is code |

---

## 9. Prerequisites

1. **`ALIYUN_KEY`** environment variable — examples use `qwen-plus`
2. Classpath mode requires `agents/travel-researcher/AGENT.md` under `src/test/resources/`
3. Code-constructed mode has no file dependencies

---

## 10. Summary

SubAgent extends Skill by giving sub-agents greater autonomy:

- **Own tools**: Tools are registered on the SubAgent itself, not dependent on the parent Agent
- **Unified interface**: Exposes as a plain `Tool` to the master Agent — same interface as Skill, freely mixable
- **Three config sources are equivalent**: classpath AGENT.md, database, and code construction produce identical behavior
- **Built-in observability**: `verbose(true)` outputs `[subagent:<name>]` prefixed logs; fine-grained callbacks for production monitoring

The next article covers SubAgent's advanced capabilities: three LLM configuration strategies (explicit injection / inherit from master / factory-by-name), `allowedTools` least-privilege borrowing, and Skill-embedded SubAgent composition.

---

> 📎 Resources
> - Full source: [Article23SubAgent.java](../../../src/test/java/org/salt/jlangchain/demo/article/Article23SubAgent.java) — methods `testSubAgentStandalone` / `testSubAgentWithMasterAgent` / `testSubAgentWithCodeConfig`
> - Sub-agent config: [agents/travel-researcher/AGENT.md](../../../src/test/resources/agents/travel-researcher/AGENT.md)
> - j-langchain GitHub: https://github.com/flower-trees/j-langchain
> - Runtime: `ALIYUN_KEY` (`qwen-plus`)
