# SubAgent Design

## 1. Background and Goals

In multi-agent systems, task decomposition follows two common patterns:

**Skill pattern**: Sub-task logic is fixed with a closed tool set — suitable for standardized sub-workflows like "check weather" or "search flights". A Skill borrows tools from its parent agent but does not own them.

**SubAgent pattern**: The sub-task requires higher autonomy — it has its own tool set, can use a different model, and can even recursively call Skills. SubAgent is suited for roles like "travel researcher" that demand multi-step reasoning and dedicated tools.

Core differences at a glance:

| Dimension | Skill | SubAgent |
|-----------|-------|----------|
| Tool source | Script tools + borrowed parent tools | Own tools + optionally borrowed parent tools |
| LLM | Inherits master agent's LLM | Can inherit, specify a model name, or receive an explicit injection |
| Knowledge injection | `references/` docs appended to system prompt | Associated Skill knowledge appended to system prompt |
| Internal composability | Can embed SubAgents (`agents/`) | Can invoke Skills (as Tools) |
| Typical use case | Standardized sub-workflows | Autonomous sub-agents, domain-specific agents |

**SubAgent's design goal**: Provide a higher-autonomy agent encapsulation compared to Skill, supporting flexible LLM configuration strategies, while maintaining the same `Tool` interface as any other tool — enabling seamless nesting.

---

## 2. Overall Architecture

```
┌─────────────────────────────────────────────────────────────────┐
│                         Master Agent                            │
│                                                                 │
│   McpAgentExecutor                                              │
│   ├─ LLM (master LLM)                                           │
│   ├─ Tool: book_flight                                          │
│   └─ Tool: travel_researcher  ←── SubAgent registered as Tool   │
│              │                                                  │
│              │ asTool()                                         │
│              ▼                                                  │
│   ┌──────────────────────────────────────────────────┐          │
│   │                    SubAgent                      │          │
│   │  config (AGENT.md)                               │          │
│   │  ownTools (explicitly registered)                │          │
│   │  inheritedTools (allowedTools injected)          │          │
│   │  callableSkills (Skills as internal Tools)       │          │
│   │           │                                      │          │
│   │           ▼  invoke()                            │          │
│   │  McpAgentExecutor (internal executor)            │          │
│   │  ├─ LLM (resolved via three-level priority)      │          │
│   │  ├─ systemPrompt (AGENT.md + Skill knowledge)   │          │
│   │  ├─ ownTools                                     │          │
│   │  ├─ inheritedTools (borrowed)                    │          │
│   │  └─ Skill Tools (callableSkills)                 │          │
│   └──────────────────────────────────────────────────┘          │
└─────────────────────────────────────────────────────────────────┘
```

---

## 3. AGENT.md Directory Layout

```
agents/travel-researcher/
  AGENT.md              ← frontmatter (name/description/model/tools/skills) + system prompt body
```

Unlike Skill, the SubAgent directory is simpler — there are no `references/`, `scripts/`, or `agents/` subdirectories. All extended capabilities (knowledge, tools, sub-workflows) are pulled in by referencing external Skills or Tools via configuration fields, not by embedding files in the directory.

### AGENT.md Format

```markdown
---
name: travel_researcher
description: Travel researcher. Queries weather, flights, and hotels to produce a travel report.
model: inherit
tools:
  - get_weather
  - search_flight
skills:
  - skills/travel-planner
max-iterations: 15
---

You are a travel researcher responsible for comprehensive travel information gathering and analysis.

Upon receiving a destination:
1. Check the weather conditions
2. Search for available flights
3. Reference the travel planning skill's best practices
4. Output a structured travel report
```

| Frontmatter field | Type | Description |
|-------------------|------|-------------|
| `name` | String | SubAgent identifier, also used as the Tool name |
| `description` | String | Tool description shown to the master LLM |
| `model` | String | LLM config: `inherit` / model name (e.g. `qwen-plus`) / omitted |
| `tools` | List | Whitelist of parent agent tool names this SubAgent may borrow |
| `skills` | List | Classpath dirs of Skills whose knowledge is injected into the system prompt |
| `max-iterations` | Integer | Maximum iterations for the internal executor, default 10 |

---

## 4. Core Data Structures

### SubAgentConfig

`SubAgentConfig` is the pure-data configuration object for a SubAgent:

```java
public class SubAgentConfig {
    private String name;
    private String description;
    private String model;              // inherit / model name / null
    private List<String> allowedTools; // whitelist for borrowing parent tools (maps to tools frontmatter field)
    private String systemPrompt;       // AGENT.md body
    private List<SkillConfig> skills;  // pre-loaded Skill configs; knowledge injected into systemPrompt
    private Integer maxIterations;

    public boolean isInheritModel() {
        return "inherit".equalsIgnoreCase(model);
    }
}
```

The `skills` field holds already-loaded `SkillConfig` objects (not path strings). The conversion from path to config is performed inside `ClasspathSubAgentConfigLoader`. A SubAgent uses Skill knowledge (system prompt + references) but does not invoke the Skill's internal executor — the knowledge is statically injected. To have a SubAgent dynamically invoke a Skill workflow, register the Skill via `Builder.skill()` in code.

---

## 5. Three-Level LLM Resolution

LLM configuration is the most distinctive design point of SubAgent, supporting three deployment scenarios:

```
Priority  Scenario                      How to configure
────────────────────────────────────────────────────────────────
1         Explicit: developer knows LLM  SubAgent.Builder.llm(llm)
2         Inherit parent LLM             model=inherit → McpAgentExecutor calls injectLlm(masterLlm)
3         Model factory: model=qwen-plus McpAgentExecutor calls injectLlmFactory(factory)
                                         → factory.apply("qwen-plus") before first invoke
```

`resolveLlm()` implementation:

```java
private BaseChatModel resolveLlm() {
    if (llm != null) return llm;                        // highest: explicit builder.llm()
    if (injectedLlm != null) return injectedLlm;        // inherit or factory-resolved
    if (llmFactory != null && config.getModel() != null
            && !config.isInheritModel()) {
        return llmFactory.apply(config.getModel());     // lowest: factory resolves by name
    }
    return null;
}
```

**Master agent injection logic** (inside `McpAgentExecutor.Builder.build()`):

```java
for (SubAgent subAgent : subAgents) {
    if (subAgent.isInheritModel()) {
        subAgent.injectLlm(llm);               // inject the master's LLM instance directly
    } else if (llmFactory != null) {
        subAgent.injectLlmFactory(llmFactory); // inject factory; sub-agent resolves by model name
    }
    ...
    tools.add(subAgent.asTool());
}
```

### Three Scenario Examples

**Scenario A: Explicit LLM (fixed model)**
```java
SubAgentConfig config = SubAgentConfig.builder()
    .name("weather_checker").systemPrompt("...").build();
SubAgent agent = SubAgent.from(config, chainActor).llm(openAiLlm).build();
```

**Scenario B: model=inherit (shared master LLM)**
```markdown
# AGENT.md frontmatter
model: inherit
```
```java
// master's build() automatically calls subAgent.injectLlm(masterLlm)
McpAgentExecutor.builder(chainActor).llm(masterLlm).subAgent(agent).build();
```

**Scenario C: model=qwen-plus (factory resolves by name)**
```markdown
# AGENT.md frontmatter
model: qwen-plus
```
```java
McpAgentExecutor.builder(chainActor)
    .llm(masterLlm)
    .llmFactory(modelName -> LlmRegistry.get(modelName))  // factory registered on the master
    .subAgent(agent)
    .build();
```

---

## 6. Two-Layer Tool Hierarchy

A SubAgent's tool set is merged from two sources (script tools are not supported, keeping SubAgent as pure-Java):

```
Source            Registration
────────────────────────────────────────────────────────
ownTools          Explicitly added via SubAgent.Builder.tools(...)
inheritedTools    Injected by McpAgentExecutor based on allowedTools
```

**allowedTools injection flow**:

```java
// Inside McpAgentExecutor.Builder.build()
if (!subAgent.getAllowedTools().isEmpty()) {
    List<Tool> allowed = tools.stream()
        .filter(t -> subAgent.getAllowedTools().contains(t.getName()))
        .toList();
    subAgent.injectParentTools(allowed);
}
```

`allowedTools` is a whitelist mechanism: the SubAgent declares which parent tools it needs, and the master agent injects only the matching subset — enforcing the principle of least privilege.

---

## 7. Skill Knowledge Injection vs. Skill Tool Registration

A SubAgent can interact with Skills in two fundamentally different ways:

**Knowledge injection (`skills` field in AGENT.md)**

```markdown
skills:
  - skills/travel-planner
```

`ClasspathSubAgentConfigLoader` appends the Skill's `systemPrompt` and `references` content to the SubAgent's system prompt:

```
[SubAgent's own systemPrompt]

---

## Skill Reference: travel_planner

[travel_planner's systemPrompt]

[travel_planner's references content]
```

The SubAgent's LLM "knows" these workflow specifications but does not actually call the Skill's executor. Well-suited for sharing collaboration conventions, output format specs, and other static knowledge.

**Tool registration (code level)**

```java
Skill travelSkill = Skill.from(skillConfig, chainActor).llm(llm).build();

SubAgent researcher = SubAgent.from(agentConfig, chainActor)
    .llm(llm)
    .skill(travelSkill)   // Skill wrapped as a Tool and registered inside SubAgent's executor
    .build();
```

The SubAgent can genuinely invoke the Skill at runtime, with the Skill running its own independent Function-Calling loop. Suited for scenarios requiring recursive sub-task delegation.

Both modes can be used simultaneously: the SubAgent both "knows" the Skill's workflow rules (knowledge injection) and can "call" the Skill to perform specific sub-tasks (tool registration).

---

## 8. Embedding SubAgents Inside a Skill (agents/ directory)

A Skill supports embedding lightweight SubAgents in its `agents/` subdirectory, which become tools in the Skill's internal executor:

```
skills/travel-planner/
  agents/
    budget-advisor.md
```

`budget-advisor.md` is a simplified SubAgent configuration (`allowed-tools` borrows tools from the Skill's own tool set):

```markdown
---
name: budget_advisor
description: Travel budget advisor. Calculates 3-night accommodation costs.
allowed-tools:
  - get_hotel_price
---

You are a travel budget advisor focused on accommodation cost estimation...
```

`ClasspathSkillConfigLoader.loadAgents()` parses `agents/*.md` into `SubAgentConfig` objects and stores them in `SkillConfig.agents`. `Skill.buildExecutor()` iterates the agents list, builds a `SubAgent` for each, and registers it:

```
Skill.buildExecutor()
  │
  ├── merge tool sources → allTools
  ├── initialize McpAgentExecutor.Builder
  └── iterate config.getAgents()
       ├── SubAgent.from(agentConfig, chainActor)
       │     .llm(skill's llm)
       │     .[verbose callbacks or custom callbacks]
       │     .build()
       └── builder.subAgent(subAgent)   ← registered in internal executor
```

The `allowed-tools` of an embedded SubAgent references tools from the Skill's own tool set (`allTools`), not the master agent's tools — the borrow scope is limited to within the Skill.

---

## 9. Observability

### Verbose Mode

```java
SubAgent.from(config, chainActor)
    .llm(llm)
    .verbose(true)
    .build();
```

Output is prefixed with `[subagent:<name>]`:

```
[subagent:travel_researcher] LLM input:
You are a travel researcher... (full prompt)
[subagent:travel_researcher] ToolCall: get_weather {"city":"Chengdu"}
[subagent:travel_researcher] Observation: Chengdu: Cloudy, 18~26°C
```

When a SubAgent is embedded inside a Skill (`agents/` directory) and the Skill has `verbose(true)` enabled, the embedded SubAgent uses a two-level prefix:

```
[skill:travel_planner>budget_advisor] ToolCall: get_hotel_price {"city":"Chengdu"}
[skill:travel_planner>budget_advisor] Observation: 3-star average ¥280/night
```

### Fine-Grained Callbacks

```java
SubAgent.from(config, chainActor)
    .llm(llm)
    .onLlm(msg -> tracer.recordPrompt(msg))
    .onToolCall(tc -> auditLog.record(tc))
    .onObservation(obs -> metricsService.recordObservation(obs))
    .build();
```

---

## 10. Stop Signal Propagation

Like Skill, a SubAgent's `invoke()` reads the parent agent's stop signal from `ContextBus` and cascades it into the internal executor:

```java
public String invoke(String input) {
    ...
    AtomicBoolean parentSignal = ContextBus.get()
        .getTransmit(CallInfo.STOP_SIGNAL.name());
    return executor.invoke(input, parentSignal).getText();
}
```

The stop signal can propagate layer by layer: master agent → SubAgent → Skills inside the SubAgent, ensuring the entire call chain halts synchronously.

---

## 11. Lazy Initialization and Injection Order

The SubAgent's internal executor uses the same double-checked locking lazy initialization. All three injection operations (`injectLlm` / `injectLlmFactory` / `injectParentTools`) set `executor` to `null`, ensuring the executor is rebuilt on the first `invoke()` after any injection:

```
SubAgent constructed
  │
  │ [possible injections]
  ├─ injectLlm()        → executor = null
  ├─ injectLlmFactory() → executor = null (only when llm==null && !inherit)
  └─ injectParentTools()→ executor = null
                │
                ▼  first invoke()
           buildExecutor()
           ├── resolveLlm()        → three-level priority chain
           ├── collectTools()      → ownTools + inheritedTools
           ├── buildSystemPrompt() → systemPrompt + Skill knowledge appended
           └── McpAgentExecutor.builder(...).build()
```

---

## 12. Package Structure

```
core/
  subagent/
    SubAgentConfig.java                  # Pure-data configuration class
    SubAgent.java                        # Core wrapper exposing asTool()/invoke()
    loader/
      SubAgentConfigLoader.java          # Loader interface
      ClasspathSubAgentConfigLoader.java # Classpath implementation, parses AGENT.md
```

`ClasspathSubAgentConfigLoader` delegates to `ClasspathSkillConfigLoader.fromClasspath()` when loading the `skills` field, creating a loader-to-loader collaboration.

---

## 13. Integration with McpAgentExecutor

`McpAgentExecutor.Builder` exposes three SubAgent-related methods:

```java
McpAgentExecutor.builder(chainActor)
    .llm(masterLlm)
    .tools(bookFlightTool, searchHotelTool)
    .subAgent(researcher)          // register a single SubAgent
    .subAgents(analyst, planner)   // register multiple SubAgents at once
    .llmFactory(modelName -> ...)  // provide factory for model=<name> SubAgents
    .build();
```

The registration logic inside `build()` guarantees the correct injection order: LLM first, then parentTools, then `asTool()` adds the SubAgent to the tool list.

---

## 14. Usage Examples

### Loading from Classpath (model=inherit)

```java
SubAgentConfig config = ClasspathSubAgentConfigLoader.fromClasspath("agents/weather-checker");
// AGENT.md frontmatter: model: inherit, tools: [get_weather]

SubAgent weatherChecker = SubAgent.from(config, chainActor).build();

McpAgentExecutor master = McpAgentExecutor.builder(chainActor)
    .llm(masterLlm)
    .tools(getWeatherTool)      // weather_checker borrows it via the tools frontmatter field
    .subAgent(weatherChecker)   // master's build() automatically injects masterLlm
    .build();
```

### SubAgent with Skill Knowledge

```java
SubAgentConfig config = ClasspathSubAgentConfigLoader.fromClasspath("agents/travel-researcher");
// AGENT.md: skills: [skills/travel-planner] ← knowledge injection, not tool invocation

SubAgent researcher = SubAgent.from(config, chainActor)
    .llm(llm)
    .tools(getWeatherTool, searchFlightTool)
    .verbose(true)
    .build();

McpAgentExecutor master = McpAgentExecutor.builder(chainActor)
    .llm(masterLlm)
    .subAgent(researcher)
    .build();
```

### Code-Only Construction + Model Factory

```java
SubAgentConfig config = SubAgentConfig.builder()
    .name("analyst")
    .model("qwen-plus")
    .systemPrompt("You are a data analyst...")
    .build();

SubAgent analyst = SubAgent.from(config, chainActor)
    // no llm set; resolved by llmFactory using the model name
    .build();

McpAgentExecutor master = McpAgentExecutor.builder(chainActor)
    .llm(defaultLlm)
    .llmFactory(name -> LlmRegistry.getOrCreate(name))
    .subAgent(analyst)
    .build();
```

---

## 15. Design Principles

| Principle | How it manifests |
|-----------|-----------------|
| **Unified interface** | SubAgent exposes only `Tool` to the master agent, indistinguishable from a regular tool |
| **LLM decoupling** | Three-level LLM priority chain covers explicit / inherit / factory deployment scenarios |
| **Least privilege** | `allowedTools` whitelist ensures a SubAgent can only access the parent tools it explicitly declares |
| **Knowledge vs. invocation separation** | Static Skill knowledge injection (system prompt) and dynamic Skill tool invocation are two orthogonal capabilities |
| **Lazy initialization** | Executor is built on the first `invoke()` after all injections; injection order is unrestricted |
| **Signal cascade** | Stop signal propagates from master agent to SubAgent via ContextBus; supports multi-level propagation |
| **Tiered observability** | `verbose` developer mode and fine-grained callbacks production mode coexist; verbose auto-adds a two-level prefix for Skill-embedded SubAgents |
