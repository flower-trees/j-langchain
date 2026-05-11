# History and Agent Memory Design

## 1. Background and Goals

LLM applications face two fundamental memory challenges:

**Conversation Memory**: In multi-turn dialogues, how do we keep the model aware of past interactions? The naive approach is to concatenate all history messages into every request, but the context window fills up quickly as the conversation grows, and costs escalate sharply.

**Agent Task Memory**: An agent makes multiple tool calls within a single task, and each call must carry the accumulated thought chain (thought / action / observation) from all previous iterations. The more complex the task and the more tools are invoked, the larger the intermediate state becomes — the same context-overflow problem applies.

The design goal of j-langchain is: **fully decouple storage backends from memory strategies, and provide an independent, pluggable strategy system for each of the two memory scenarios**, so developers can freely combine storage and compression strategies without touching business logic.

---

## 2. Overall Architecture

```
┌─────────────────────────────────────────────────────────────┐
│                  j-langchain Memory System                   │
│                                                             │
│  ┌─────────────────────────┐  ┌──────────────────────────┐  │
│  │   Conversation Memory   │  │    Agent Task Memory     │  │
│  │  (multi-turn dialogue)  │  │  (single-task execution) │  │
│  │                         │  │                          │  │
│  │  MemoryStrategy         │  │  AgentContext (factory)  │  │
│  │  ├─ Buffer              │  │  ├─ FullContext           │  │
│  │  ├─ BufferWindow        │  │  └─ SlidingWindowContext  │  │
│  │  ├─ Summary             │  │                          │  │
│  │  └─ SummaryBuffer       │  │  AgentTaskContext        │  │
│  │                         │  │  (per-invocation session)│  │
│  │  ConversationStorage    │  │  AgentTaskStorage        │  │
│  │  ├─ InMemory            │  │  ├─ InMemory             │  │
│  │  └─ (MySQL/Redis/...)   │  │  └─ (MySQL/Redis/...)    │  │
│  └─────────────────────────┘  └──────────────────────────┘  │
│                                                             │
│              HistoryInfos (unified data model)              │
└─────────────────────────────────────────────────────────────┘
```

The two subsystems share a single data model, `HistoryInfos`, and are linked via `parentId` into a logical parent-child relationship: a conversation record in `ConversationStorage` can serve as the parent node for multiple agent steps in `AgentTaskStorage`.

---

## 3. Unified Data Model: HistoryInfos

`HistoryInfos` is the core data structure that runs through the entire memory system:

```java
public class HistoryInfos {

    public enum Type {
        NORMAL,        // ordinary Human + AI dialogue turn
        SUMMARY,       // conversation summary (replaces earlier turns)
        AGENT_STEP,    // agent tool-call step
        TASK_SUMMARY   // compressed summary of agent steps
    }

    private String id;          // UUID, auto-generated
    private String parentId;    // parent record id (null = top-level)
    private long   createdAt;   // creation timestamp (epoch ms)
    private Type   type;
    private List<BaseMessage> messages;
}
```

**Design notes:**

- `id` + `parentId` form a tree: ConversationStorage record (root) → AgentTaskStorage steps (children) → nested agent steps (grandchildren).
- The `type` enum distinguishes four record semantics, allowing both the storage and retrieval layers to handle each type differently without extra tables or collections.
- `messages` stores a uniform `List<BaseMessage>`. `BaseMessage` uses Jackson `@JsonTypeInfo(EXISTING_PROPERTY)` to leverage the existing `role` field for polymorphic deserialization, enabling persistence to MySQL, Redis, and other backends.

**Tree storage illustration:**

```
ConversationStorage
  └─ HistoryInfos { id="task-001", parentId=null, type=NORMAL }     ← final dialogue turn

AgentTaskStorage
  └─ HistoryInfos { id="step-1", parentId="task-001", type=AGENT_STEP }      ← tool call step 1
  └─ HistoryInfos { id="step-2", parentId="task-001", type=AGENT_STEP }      ← tool call step 2
  └─ HistoryInfos { id="summary-1", parentId="task-001", type=TASK_SUMMARY } ← replaces old steps
```

---

## 4. Conversation Memory

### 4.1 Two-Layer Separation

Conversation memory uses a **two-layer separation** design:

```
Storage layer                  Memory strategy layer
──────────────────             ──────────────────────────────
ConversationStorage   ←→       ConversationMemoryReaderBase
  ├─ loadAll()                 ConversationMemoryStorerBase
  ├─ append()                    ├─ Buffer
  ├─ replace()                   ├─ BufferWindow
  └─ clear()                     ├─ Summary
                                 └─ SummaryBuffer
```

- The **storage layer** is responsible solely for raw CRUD; it has no knowledge of memory strategies.
- The **strategy layer** is responsible solely for compression / truncation logic; it reads and writes through the storage interface and does not care whether the backend is in-memory or a database.

This means swapping the storage backend (in-memory → MySQL → Redis) has zero impact on strategy code, and adding a new strategy has zero impact on storage code.

### 4.2 Storage Interface

```java
public interface ConversationStorage {
    List<HistoryInfos> loadAll(Long appId, Long userId, Long sessionId);
    void append(Long appId, Long userId, Long sessionId, HistoryInfos turn);
    void replace(Long appId, Long userId, Long sessionId, List<HistoryInfos> compacted);
    void clear(Long appId, Long userId, Long sessionId);
}
```

`replace()` is the key operation: Summary-based strategies use it to atomically replace old records with a compressed result after summarization, preventing read-write inconsistencies under concurrency.

### 4.3 Four Memory Strategies

| Strategy | What is stored | Best for |
|----------|---------------|---------|
| **Buffer** | All turns retained | Short dialogues with bounded context |
| **BufferWindow** | Only the most recent N turns | Long dialogues focused on recent context |
| **Summary** | An LLM-generated summary replaces all history each turn | Very long dialogues where key information must be distilled |
| **SummaryBuffer** | Most recent N turns in full + an early summary | Balance between precision and window size |

#### Buffer (Full Buffer)

The simplest strategy: append every turn, never compress. Suitable when the number of dialogue turns is predictable.

```
[NORMAL: H1+A1] → [NORMAL: H1+A1] → [NORMAL: H2+A2]
                                     append each turn, return all
```

#### BufferWindow (Sliding Window)

When the number of turns exceeds `maxSize`, remove the oldest. Simple and efficient, but dropped early turns are unrecoverable.

```
window=2:
  [H1+A1, H2+A2] → new turn → [H2+A2, H3+A3]  (H1+A1 discarded)
```

#### Summary (Full Summary)

After each turn is stored, call an LLM to compress all history into a single `SUMMARY` record. The next read injects the summary into the system prompt. Information loss is bounded, but every turn incurs an LLM summarization cost.

```
After store: [SUMMARY: "The user asked about weather and time..."]  (originals replaced)
At read:     SystemMessage("Here is the summary: ...")
```

#### SummaryBuffer (Summary + Buffer Hybrid)

Keeps the most recent N turns verbatim while maintaining a single early-history summary. Balances recent precision with long-term memory.

```
window=2, history=[S, H1+A1, H2+A2]:
  new turn H3+A3 arrives →
  H1+A1 is compressed into updated summary S' →
  stored: [S', H2+A2, H3+A3]
```

### 4.4 Reader / Storer Separation

Each strategy is split into a standalone `Reader` and a standalone `Storer`:

- **Reader** runs as a chain node before the LLM call; it loads history from storage and injects it into the current message list.
- **Storer** runs as a chain node after the LLM response; it writes the current Human + AI messages to storage (and compresses according to the strategy).

The two are configured independently, enabling read-only (replay history only) or write-only (record but do not replay) scenarios.

---

## 5. Agent Task Memory

### 5.1 Core Challenge

Each iteration of an agent's tool-calling loop produces new AI messages and tool result messages. These must be passed in full to the next LLM call; otherwise the model loses context. When tool-call iterations are numerous, the accumulated messages can exceed the context window.

### 5.2 Design Philosophy: Strategy + ContextBus

Agent task memory is built around two core ideas:

**Strategy Pattern**: Through the `AgentContext` factory interface, the choice of compression strategy is extracted from the executor. `AgentExecutor` and `McpAgentExecutor` depend only on the interface and are unaware of the concrete strategy.

**ContextBus propagation**: The `AgentTaskContext` created per `invoke()` call is stored in `ContextBus`. Chain nodes read it through `ContextBus` rather than through constructor parameters or ThreadLocal. This keeps nodes stateless and naturally thread-safe.

```
invoke() starts
   │
   ▼
initContext (chain node)
   │  AgentContext.create(question, systemPrompt)
   │  → AgentTaskContext ctx
   │  → ContextBus.putTransmit(AGENT_TASK_CTX, ctx)
   │  → ContextBus.putTransmit(QUESTION, question)
   │
   ▼
LLM call (iteration 1)
   │
   ▼
executeTool (chain node)
   │  ctx = ContextBus.getTransmit(AGENT_TASK_CTX)
   │  execute tool → toolResults
   │  ctx.addStep(AgentStep.ofFunctionCall(aiMsg, toolResults))
   │  return ctx.buildChatPromptValue()   ← build messages for next iteration
   │
   ▼
LLM call (iteration 2) ... (loop until Final Answer)
   │
   ▼
conversationStorer (optional, chain node)
   │  reads QUESTION from ContextBus
   │  writes to ConversationStorage
```

### 5.3 Core Interfaces

#### AgentContext (strategy factory)

```java
public interface AgentContext {
    AgentTaskContext create(String question, String systemPrompt);
}
```

One `create()` call per `invoke()` returns an isolated `AgentTaskContext` instance. Concurrent requests are completely isolated from one another.

#### AgentTaskContext (per-task working memory)

```java
public interface AgentTaskContext {
    void addStep(AgentStep step);
    List<BaseMessage> buildMessages();                   // function-calling mode
    default ChatPromptValue buildChatPromptValue() { ... }
    void initReactBasePromptText(String text);           // ReAct text mode
    String buildReactPromptText();
    String getTaskId();
    default List<AgentStep> getCompletedSteps() { ... }
}
```

Both agent modes (function-calling and ReAct text) share the same interface; implementations handle each mode separately:
- Function-calling: `addStep` accumulates `AIMessage + ToolMessages`; `buildMessages()` reconstructs the complete message list.
- ReAct text: `addStep` accumulates scratchpad text blocks; `buildReactPromptText()` concatenates the full prompt text.

#### AgentStep (single execution step)

```java
public class AgentStep {
    private AIMessage aiMessage;          // function-calling mode
    private List<BaseMessage> toolResults;
    private String scratchpadText;        // ReAct text mode

    public static AgentStep ofFunctionCall(AIMessage aiMsg, List<BaseMessage> results) { ... }
    public static AgentStep ofReAct(String scratchpadText) { ... }
    public List<BaseMessage> toMessages() { ... }
}
```

Factory methods make the mode explicit and prevent accidental mixing.

### 5.4 Two AgentContext Implementations

#### FullContext (full history, default strategy)

No compression at all; all steps are retained as-is. This is the default when `.context()` is not configured.

```java
public class FullContext implements AgentContext {
    public static FullContext build() { return new FullContext(); }
}
```

```
step 1 → [step1]
step 2 → [step1, step2]
step 3 → [step1, step2, step3]   (unbounded growth)
```

#### SlidingWindowContext (sliding window + optional summary)

Maintains a fixed-size step window. When the number of steps exceeds `windowSize`, the oldest step is evicted from the window and:
- If a `summarizer` LLM is configured: the LLM generates or updates an `earlyStepsSummary`.
- If no `summarizer` is configured: plain-text concatenation updates `earlyStepsSummary`.

In function-calling mode, `earlyStepsSummary` is injected into the system prompt. In ReAct mode, it is used as a scratchpad prefix.

```
windowSize=2:
step 1 → recentSteps=[step1]
step 2 → recentSteps=[step1, step2]
step 3 → compression triggered:
         earlyStepsSummary = summarize(step1)
         recentSteps=[step2, step3]
step 4 → compression triggered:
         earlyStepsSummary = summarize(earlyStepsSummary + step2)
         recentSteps=[step3, step4]
```

Message layout when building the prompt:

```
[SystemMessage: originalSystemPrompt + "\n\n" + earlyStepsSummary]
[HumanMessage: originalTask]
[messages from step N-1 ...]
[messages from step N ...]
```

**Builder configuration:**

```java
SlidingWindowContext.builder()
    .windowSize(5)                  // keep the most recent 5 steps
    .summarizer(summarizerLlm)      // optional LLM summarizer
    .taskStorage(inMemoryStorage)   // optional persistence backend
    .build()
```

### 5.5 AgentTaskStorage (step persistence)

`AgentTaskStorage` is an optional step persistence backend, designed symmetrically with `ConversationStorage`:

```java
public interface AgentTaskStorage {
    void append(String taskId, HistoryInfos step);
    List<HistoryInfos> loadByTaskId(String taskId);
    void replace(String taskId, List<HistoryInfos> compacted);
    void clear(String taskId);
}
```

`SlidingWindowContext` calls `append()` on every `addStep()` and `replace()` during compression (replacing old `AGENT_STEP` records with a `TASK_SUMMARY` record). This provides a complete execution trace for debugging, auditing, and offline analysis.

---

## 6. ConversationStorer and Agent Integration

The final Human + AI dialogue turn of an agent task is written to `ConversationStorage` via `conversationStorer`, bridging "intra-task execution memory" with "cross-session conversation memory".

```java
McpAgentExecutor.builder(chainActor)
    .llm(llm)
    .tools(tools)
    .context(SlidingWindowContext.builder().windowSize(3).taskStorage(taskStorage).build())
    .conversationStorer(ConversationBufferMemoryStorer.builder()
        .storage(conversationStorage)
        .appId(appId).userId(userId).sessionId(sessionId)
        .build())
    .build();
```

`conversationStorer` is attached as a chain node at the tail of the execution chain. It reuses the existing `ConversationMemoryStorerBase` infrastructure; the agent layer is completely unaware of any storage details. `QUESTION` is written into ContextBus by the `initContext` node and read directly by the storer.

---

## 7. Package Structure

```
core/
  history/
    HistoryInfos.java                    # unified data model
    HistoryBase.java                     # appId/userId/sessionId base class
    HistoryReaderBase.java               # Reader chain node base class
    HistoryStorerBase.java               # Storer chain node base class
    storage/
      ConversationStorage.java           # storage interface
      InMemoryConversationStorage.java   # in-memory implementation
    memory/
      ConversationMemoryReaderBase.java  # memory Reader base class
      ConversationMemoryStorerBase.java  # memory Storer base class
      buffer/                            # Buffer strategy
      bufferwindow/                      # BufferWindow strategy
      summary/                           # Summary strategy
      summarybuffer/                     # SummaryBuffer strategy

  agent/
    storage/
      AgentTaskStorage.java              # agent step storage interface
      InMemoryAgentTaskStorage.java      # in-memory implementation
    memory/
      AgentContext.java                  # strategy factory interface
      AgentTaskContext.java              # per-task working memory interface
      AgentStep.java                     # single execution step
      FullContext.java                   # full-history strategy implementation
      SlidingWindowContext.java          # sliding window strategy implementation
    AgentExecutor.java                   # ReAct text agent
    McpAgentExecutor.java                # function-calling agent
```

---

## 8. Extension Guide

### 8.1 Adding a New Storage Backend

Implement `ConversationStorage` or `AgentTaskStorage` and inject it into the strategy class. No existing code needs to change:

```java
@Component
public class RedisConversationStorage implements ConversationStorage {
    // implement loadAll / append / replace / clear
}
```

`BaseMessage` already has Jackson polymorphic annotations configured and supports JSON serialization / deserialization, making it safe to store in Redis or MySQL directly.

### 8.2 Adding a New Conversation Memory Strategy

Extend `ConversationMemoryReaderBase` and `ConversationMemoryStorerBase` and implement their abstract methods:

```java
public class MyCustomMemoryStorer extends ConversationMemoryStorerBase {
    @Override
    protected void storeHistory(HistoryInfos turn) {
        // custom compression / storage logic
    }
}
```

### 8.3 Adding a New Agent Task Memory Strategy

Implement `AgentContext`; the inner `Session` class implements `AgentTaskContext`:

```java
public class TokenBudgetContext implements AgentContext {
    @Override
    public AgentTaskContext create(String question, String systemPrompt) {
        return new Session(question, systemPrompt, tokenBudget);
    }
    // Session compresses by token count rather than step count
}
```

---

## 9. Design Principles

| Principle | How It Is Applied |
|-----------|-------------------|
| **Separation of concerns** | Storage layer and strategy layer are fully independent; each has a single responsibility |
| **Interface-driven** | `ConversationStorage`, `AgentContext`, and `AgentTaskContext` are all interfaces; easy to replace and test |
| **Strategy pattern** | Four conversation memory strategies and two agent memory strategies are unified behind interfaces and injected at runtime |
| **Stateless nodes** | Chain nodes pass state through `ContextBus` and hold no instance-level state; naturally thread-safe |
| **Unified data model** | `HistoryInfos` spans both subsystems; `parentId` builds cross-system tree relationships |
| **Progressive configuration** | All advanced features (windowing, summarization, persistence) are optional; the default behavior works out of the box |
