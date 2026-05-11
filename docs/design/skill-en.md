# Skill Design

## 1. Background and Goals

As LLM applications evolve from single-agent to multi-agent collaboration, a core challenge emerges: **how to encapsulate complex sub-task logic into an atomic unit that is transparent to the master agent?**

The naive approach is to pile all tools and logic into one agent, which causes three pain points:

- **Poor reusability**: The same sub-workflow (e.g. "check weather", "search flights") is reimplemented in every agent.
- **System prompt bloat**: The master agent must understand every sub-task in detail, making its system prompt increasingly long.
- **Poor testability**: Individual sub-workflows cannot be tested in isolation — only end-to-end execution can verify them.

**Skill** is designed to package the complete workflow of a sub-task (system prompt + tool set + knowledge base + executable scripts) into a self-contained unit, exposed to the master agent as a plain `Tool`, running a full Function-Calling loop internally. The master agent needs no knowledge of Skill internals — it simply passes an input and waits for the result.

---

## 2. Overall Architecture

```
┌─────────────────────────────────────────────────────────────────┐
│                         Master Agent                            │
│                                                                 │
│   McpAgentExecutor                                              │
│   ├─ LLM                                                        │
│   ├─ Tool: search_web                                           │
│   ├─ Tool: send_email                                           │
│   └─ Tool: travel_planner  ←── Skill registered as a plain Tool │
│              │                                                  │
│              │ asTool()                                         │
│              ▼                                                  │
│        ┌─────────────────────────────────┐                      │
│        │           Skill                 │                      │
│        │  config  (SKILL.md)             │                      │
│        │  ownTools (explicitly registered)│                     │
│        │  parentTools (allowedTools inject)│                    │
│        │         │                       │                      │
│        │         ▼  invoke()             │                      │
│        │  McpAgentExecutor (internal)    │                      │
│        │  ├─ LLM (inherited from master) │                      │
│        │  ├─ systemPrompt (SKILL.md body)│                      │
│        │  ├─ ScriptTools (scripts/*)     │                      │
│        │  ├─ ownTools                    │                      │
│        │  ├─ parentTools (borrowed)      │                      │
│        │  └─ SubAgents (agents/* embed)  │                      │
│        └─────────────────────────────────┘                      │
└─────────────────────────────────────────────────────────────────┘
```

**Core design philosophy**: A Skill is a Tool on the outside and a complete Agent on the inside. The master agent's tool-call protocol is fully decoupled from Skill's internal execution protocol.

---

## 3. SKILL.md Directory Layout

Skills follow a convention-over-configuration file layout:

```
skills/travel-planner/
  SKILL.md              ← frontmatter (name/description/allowed-tools) + system prompt body
  references/           ← domain knowledge documents injected into the system prompt
    destinations.md
    booking-policy.md
  scripts/              ← executable scripts, automatically converted to Tools
    get_weather.py
    search_flight.sh
  agents/               ← embedded sub-agents, registered as internal Tools
    budget-advisor.md
```

### SKILL.md Format

```markdown
---
name: travel_planner
description: Travel planning expert. Call when the user needs to plan a trip.
allowed-tools:
  - search_hotel
  - book_ticket
max-iterations: 8
---

You are an experienced travel planning expert. Based on user requirements, devise a complete itinerary.

Workflow:
1. Check the weather at the destination
2. Search for available flights and hotels
3. Estimate the travel budget
4. Output a structured itinerary
```

| Frontmatter field | Type | Description |
|-------------------|------|-------------|
| `name` | String | Skill identifier, also used as the Tool name |
| `description` | String | Tool description shown to the master LLM, influences routing decisions |
| `allowed-tools` | List | Names of parent agent tools this Skill is allowed to borrow |
| `max-iterations` | Integer | Maximum iterations for the internal executor, default 10 |

---

## 4. Core Data Structures

### SkillConfig

`SkillConfig` is the pure-data configuration object for a Skill — the output of a loader (`ClasspathSkillConfigLoader`) and the input to `Skill.from()`:

```java
public class SkillConfig {
    private String name;
    private String description;
    private List<String> allowedTools;   // tools allowed to borrow from parent
    private String systemPrompt;          // SKILL.md body
    private List<String> references;      // pre-loaded content from references/*.md
    private List<ScriptDef> scripts;      // script definitions from scripts/*
    private List<SubAgentConfig> agents;  // embedded sub-agents from agents/*.md
    private Integer maxIterations;
}
```

`SkillConfig` is storage-agnostic — the same structure can be loaded from the classpath (`ClasspathSkillConfigLoader`), from a database, or constructed in code.

### ScriptDef

```java
public class ScriptDef {
    private String name;     // tool name (filename without extension)
    private String type;     // extension: py / sh / js / rb
    private String content;  // script source code
}
```

---

## 5. Three-Layer Tool Hierarchy

The internal executor's tool set is merged from three sources:

```
Priority  Source            Registration
────────────────────────────────────────────────────────
1         ScriptTools       Auto-converted from SKILL.md scripts/*
2         ownTools          Explicitly added via Skill.Builder.tools(...)
3         parentTools       Injected by McpAgentExecutor based on allowedTools
```

**ScriptTools**

Each script file is written to a system temp directory when `ScriptTool.from(ScriptDef)` is called, then executed via `ProcessBuilder`. Only stdout is captured as the tool's return value — the script source never enters any LLM context:

```
Extension   Executor
────────────────────
.py         python
.sh         bash
.js         node
.rb         ruby
```

Register a custom executor:

```java
ScriptTool.register("groovy", "groovy");
```

**parentTools (borrowed tools)**

`allowedTools` declares the whitelist of tool names the Skill wishes to borrow from the master agent. The master agent filters and injects them automatically at build time:

```java
// Inside McpAgentExecutor.Builder.build()
for (Skill skill : skills) {
    List<Tool> allowed = tools.stream()
        .filter(t -> skill.getAllowedTools().contains(t.getName()))
        .toList();
    skill.injectParentTools(allowed);
    tools.add(skill.asTool());
}
```

---

## 6. Knowledge Injection (references)

Documents in `references/*.md` are pre-loaded into memory and appended to the system prompt, separated by `---`:

```
[SKILL.md body]

---

[references/destinations.md content]

---

[references/booking-policy.md content]
```

This way the Skill's internal LLM can "see" domain knowledge on every call without the overhead of an additional RAG retrieval step. Well-suited for small, stable reference documents (price lists, policy texts, workflow specifications, etc.).

---

## 7. Embedded Sub-Agents (agents/ directory)

A Skill can embed lightweight `SubAgent`s for further task decomposition:

```
skills/travel-planner/
  agents/
    budget-advisor.md   ← embedded sub-agent with access to get_hotel_price
```

`budget-advisor.md` format (supports both frontmatter and plain-body variants):

```markdown
---
name: budget_advisor
description: Travel budget advisor. Calculates 3-night accommodation costs for a city.
allowed-tools:
  - get_hotel_price
---

You are a travel budget advisor focused on accommodation cost estimation.
When given a city name, call get_hotel_price to get the average hotel rate and calculate the 3-night total.
```

`ClasspathSkillConfigLoader` parses `agents/*.md` into a list of `SubAgentConfig` objects at load time. `Skill.buildExecutor()` builds a `SubAgent` from each `SubAgentConfig` and registers it via `McpAgentExecutor.Builder.subAgent()`:

```
Skill.buildExecutor()
  │
  ├── merge three tool sources → allTools
  ├── initialize McpAgentExecutor.Builder
  └── iterate config.getAgents()
       └── SubAgent.from(agentConfig, chainActor).llm(skill's llm).build()
           └── builder.subAgent(subAgent)
```

Embedded sub-agents inherit the Skill's own LLM by default (since Skill only has a single `llm` field).

---

## 8. Observability

### Verbose Mode

`Builder.verbose(true)` automatically generates prefixed console logs for the Skill and its embedded sub-agents:

```
[skill:travel_planner] LLM input:
You are an experienced travel planning expert... (full prompt)
[skill:travel_planner] ToolCall: get_weather {"city":"Chengdu"}
[skill:travel_planner] Observation: Chengdu: Cloudy, 18~26°C

[skill:travel_planner>budget_advisor] ToolCall: get_hotel_price {"city":"Chengdu"}
[skill:travel_planner>budget_advisor] Observation: 3-star average ¥280/night
```

Embedded sub-agents use a two-level prefix `[skill:<skillName>><agentName>]`, visually distinct from the parent Skill's logs.

### Fine-Grained Callbacks

```java
Skill.from(config, chainActor)
    .llm(llm)
    .onLlm(msg -> metricsService.recordPromptTokens(msg))
    .onToolCall(tc -> auditLog.record("tool_call", tc))
    .onObservation(obs -> tracer.span("observation", obs))
    .build();
```

| Callback | Triggered | Argument |
|----------|-----------|----------|
| `onLlm` | Before each LLM call | Full prompt text |
| `onToolCall` | Before each tool invocation | `"{toolName} {argsJson}"` |
| `onObservation` | After each tool returns | Tool result string |

`verbose(true)` and custom callbacks are mutually exclusive: enabling verbose overwrites any custom callbacks; calling `verbose(false)` clears them.

---

## 9. Stop Signal Propagation

When a Skill is invoked, it reads the parent agent's stop signal from the `ContextBus`:

```java
// Inside Skill.invoke()
AtomicBoolean parentSignal = ContextBus.get()
    .getTransmit(CallInfo.STOP_SIGNAL.name());
return executor.invoke(input, parentSignal).getText();
```

When the master agent is stopped externally, the stop signal cascades into the Skill's internal executor via `ContextBus.transmit`. The Skill will detect and abort before the next tool call, preventing resource leaks.

---

## 10. Lazy Initialization

The internal `McpAgentExecutor` is lazily initialized with double-checked locking:

```java
public String invoke(String input) {
    if (executor == null) {
        synchronized (this) {
            if (executor == null) {
                executor = buildExecutor();
            }
        }
    }
    ...
}
```

Calling `injectParentTools()` sets `executor` to `null`, forcing a rebuild on the next invocation. This ensures tools are injected before the executor is built, while avoiding unnecessary construction overhead.

---

## 11. Package Structure

```
core/
  skill/
    SkillConfig.java              # Pure-data configuration class
    Skill.java                    # Core wrapper exposing asTool()/invoke()
    ScriptDef.java                # Script definition (name/type/source)
    ScriptTool.java               # Script → Tool converter (ProcessBuilder execution)
    loader/
      SkillConfigLoader.java      # Loader interface
      ClasspathSkillConfigLoader.java  # Classpath implementation, parses SKILL.md directory
```

---

## 12. Usage Examples

### Loading from Classpath

```java
// Load from resources/skills/travel-planner/
SkillConfig config = ClasspathSkillConfigLoader.fromClasspath("skills/travel-planner");

Skill travelPlanner = Skill.from(config, chainActor)
    .llm(llm)
    .verbose(true)
    .build();

McpAgentExecutor master = McpAgentExecutor.builder(chainActor)
    .llm(llm)
    .tools(searchHotelTool, bookTicketTool)  // borrowed by travel_planner via allowedTools
    .skill(travelPlanner)
    .build();
```

### Code-Only Construction (No Files)

```java
SkillConfig config = SkillConfig.builder()
    .name("weather_skill")
    .description("Query city weather")
    .systemPrompt("You are a weather expert. When given a city name, call the tool to retrieve current conditions.")
    .build();

Skill weatherSkill = Skill.from(config, chainActor)
    .llm(llm)
    .tools(getWeatherTool)   // register tool directly, no allowedTools needed
    .build();
```

---

## 13. Design Principles

| Principle | How it manifests |
|-----------|-----------------|
| **Encapsulation as interface** | Skill exposes only `Tool` to the master agent; internals are fully opaque |
| **Convention over configuration** | The `SKILL.md` directory layout (references/scripts/agents) has well-defined loading conventions |
| **Three-layer tool sourcing** | Script tools / explicit tools / borrowed tools are cleanly separated and non-interfering |
| **Lazy initialization** | The internal executor is built on the first `invoke()` call, supporting post-construction injection |
| **Tiered observability** | `verbose` for development debugging; fine-grained callbacks for production monitoring |
| **Signal cascade** | Stop signal propagates from master agent into the Skill via ContextBus, preventing resource leaks |
