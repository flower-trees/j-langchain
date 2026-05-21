# Agent Stop and Resume Design

## 1. Background and Goals

When an agent executes long-running tasks — multiple tool calls, nested Skills or SubAgents — two control requirements arise:

**Stop**: An external thread (HTTP cancellation, user interrupt, timeout guard) needs to terminate a running agent loop at any point, including deeply nested Skills/SubAgents, while preserving the execution state of all steps completed so far.

**Resume / Continue**: After a stop or failure, resume execution from the saved context for the same task, or inject completed steps into a new task instruction to avoid redundant tool calls.

The design goal of j-langchain is: **implement cross-layer stop via a shared signal + ContextBus propagation without modifying the salt-function-flow engine's core semantics, and support transparent resumption through a pre-loadable `AgentTaskContext`** — the agent itself does not need to distinguish between a fresh start and a resume.

---

## 2. Why Not Use FlowInstance.stop()

`FlowInstance.stop()` calls `ContextBus.get().stopProcess()`. `ContextBus` is `ThreadLocal`-based: each `FlowInstance.execute()` call creates an instance that belongs exclusively to the calling thread.

```
Execution thread:  FlowInstance.execute() → ContextBus (ThreadLocal A)
Control thread:    FlowInstance.stop()    → ContextBus.get() → ThreadLocal B (different instance)
                                          ↑ ineffective — the signal never reaches the execution thread
```

Cross-thread control requires **shared mutable state on the heap**, not ThreadLocal.

---

## 3. Core Mechanism: AtomicBoolean Signal

### 3.1 Signal Lifecycle

On every `invoke()` call:
- If no external signal is provided, a new `AtomicBoolean(false)` is created and held by the executor.
- If an external signal is provided (SubAgent/Skill propagation path), that reference is used directly.
- The signal reference is injected into `ContextBus.transmitMap` under the key `CallInfo.STOP_SIGNAL`.

`stop()` does exactly one thing: set the signal to `true`.

```java
public void stop() {
    stopSignal.set(true);   // thread-safe; immediately visible to all threads sharing this reference
}
```

### 3.2 Check Point: shouldContinue

At the start of every loop iteration, `shouldContinue` checks the stop signal first:

```java
Function<Integer, Boolean> shouldContinue = i -> {
    AtomicBoolean signal = ContextBus.get().getTransmit(CallInfo.STOP_SIGNAL.name());
    if (signal != null && signal.get()) {
        AgentTaskContext ctx = ContextBus.get().getTransmit(CallInfo.AGENT_TASK_CTX.name());
        throw new AgentStoppedException("Agent stopped by external request", ctx);
    }
    // ... normal loop condition
};
```

The check point is in the loop condition, not inside tool execution. This means:
- If `stop()` is called while a tool is running, the current tool **completes normally** and stop takes effect at the next `shouldContinue` check.
- Tool atomicity is preserved; no half-completed steps are produced.

### 3.3 AgentStoppedException

When the stop signal fires, `AgentStoppedException` is thrown carrying the full context at that moment:

```java
public class AgentStoppedException extends RuntimeException
        implements FlowControlException {   // see Section 6

    private final AgentTaskContext partialContext;

    public AgentTaskContext getPartialContext() { return partialContext; }

    // Convenience: returns completed steps, or an empty list if context is null
    public List<AgentStep> getCompletedSteps() {
        return partialContext != null ? partialContext.getCompletedSteps() : List.of();
    }
}
```

The top-level catch block in `invoke()`:

```java
try {
    return (ChatGeneration) chainActor.invoke(agentChain, ...);
} catch (AgentStoppedException e) {
    if (conversationStorer != null) conversationStorer.savePartial(e.getPartialContext());
    throw e;   // propagate to the caller
}
```

---

## 4. Stop Signal Propagation into Skills and SubAgents

Skills and SubAgents each run their own `McpAgentExecutor` instance with a separate `FlowInstance` and ContextBus. The signal must be passed explicitly.

Propagation path: master agent ContextBus → read at tool invocation time → passed as `externalSignal` to the child executor.

```java
// SubAgent.invoke() / Skill.invoke()
AtomicBoolean parentSignal = null;
if (ContextBus.get() != null) {
    parentSignal = ((IContextBus) ContextBus.get())
            .getTransmit(CallInfo.STOP_SIGNAL.name());
}
return executor.invoke(input, parentSignal).getText();
```

The child executor injects this signal into its own ContextBus transmit map so its `shouldContinue` observes the same `AtomicBoolean` reference.

```
master.stop() → stopSignal.set(true)
                   │
                   ▼
master shouldContinue or executeTool
                   │
                   ▼
SubAgent.invoke(input, parentSignal)   ← same AtomicBoolean reference
                   │
                   ▼
sub shouldContinue → signal.get() == true → AgentStoppedException
                   │
                   ▼
executeTool (master) receives AgentStoppedException
   ↓ catch(AgentStoppedException e) { throw e; }   ← not treated as a tool error; re-thrown directly
                   │
                   ▼
master invoke() catches → savePartial() + propagate to caller
```

In `executeTool`'s exception handler, `AgentStoppedException` must be caught before the generic `Exception` handler; otherwise it is mis-logged as a tool execution error:

```java
try {
    Object raw = tool.getFunc().apply(argsMap);
    ...
} catch (AgentStoppedException e) {
    log.debug("Tool '{}' interrupted by stop signal", toolName);
    throw e;                          // re-throw without setting an observation
} catch (Exception e) {
    observation = "Tool execution error: " + e.getMessage();
    log.error("Tool execution failed: {}", toolName, e);
}
```

---

## 5. Resume Execution

### 5.1 Pre-loading Context

The core of resumption is reusing the `AgentTaskContext` saved at stop time instead of creating a blank context.

The caller retrieves `partialContext` from `AgentStoppedException` and passes it to the next `invoke()`:

```java
AgentTaskContext partialCtx = stoppedException.getPartialContext();
ChatGeneration result = agent.invoke(question, partialCtx);
```

The `initContext` node prefers the pre-loaded context:

```java
AgentTaskContext ctx = ContextBus.get().getTransmit(CallInfo.PRELOADED_CTX.name());
if (ctx == null) {
    ctx = contextFinal.create(question, systemPromptFinal);   // normal first run
}
ContextBus.get().putTransmit(CallInfo.AGENT_TASK_CTX.name(), ctx);
```

### 5.2 The LLM's First Call on Resume Must See Prior Steps

There is an important subtlety: the `prompt` node (`ChatPromptTemplate`) outputs a `ChatPromptValue` containing only `[system?, human]` — it does not include the steps already in `AgentTaskContext`. If this is fed directly into the loop, the LLM's first call knows nothing about prior tool calls and repeats them.

Solution: insert an `applyPreloadedSteps` node between `prompt` and `loop`:

```java
TranslateHandler<Object, Object> applyPreloadedSteps = new TranslateHandler<>(promptValue -> {
    AgentTaskContext ctx = ContextBus.get().getTransmit(CallInfo.AGENT_TASK_CTX.name());
    if (ctx != null && !ctx.getCompletedSteps().isEmpty()) {
        return ctx.buildChatPromptValue();   // replace with full context including prior steps
    }
    return promptValue;   // first run — pass through unchanged
});

// Chain layout:
.next(initContext)
.next(prompt)
.next(applyPreloadedSteps)   // fill in prior steps when resuming
.loop(shouldContinue, ...)
```

LLM input on the first resumed iteration:

```
user:      查询成都的旅游信息和酒店价格，然后给出建议
assistant(tool_calls): search_city {"city":"成都"}
tool[search_city]: 成都: 旅游信息...
```

The LLM sees the existing tool results and can continue from where execution stopped rather than restarting.

### 5.3 Continue with a New Instruction (createWithSteps)

`AgentContext` provides a default `createWithSteps()` method that lets callers inject prior steps into a fresh context for a different instruction:

```java
// AgentContext.java
default AgentTaskContext createWithSteps(String question, String systemPrompt,
                                          List<AgentStep> priorSteps) {
    AgentTaskContext ctx = create(question, systemPrompt);
    if (priorSteps != null) {
        priorSteps.forEach(ctx::addStep);
    }
    return ctx;
}
```

Use case: after an interruption, the caller decides to switch to a new question while reusing already-gathered data:

```java
List<AgentStep> priorSteps = stoppedException.getCompletedSteps();
AgentTaskContext newCtx = context.createWithSteps(newQuestion, null, priorSteps);
ChatGeneration result = agent.invoke(newQuestion, newCtx);
```

---

## 6. Integration with salt-function-flow: FlowControlException

`FlowNodeLoop.doProcessGateway` records every exception thrown during node execution through `ContextBus.putException()`, which by default logs at WARN level with a full stack trace:

```java
// FlowNodeLoop.java
try {
    execute(info);
} catch (Exception e) {
    ((ContextBus) iContextBus).putException(info.getIdOrAlias(), e);
    throw e;
}
```

`AgentStoppedException` is an expected control-flow signal, not a failure. Logging it as WARN or ERROR is misleading.

Solution: introduce a `FlowControlException` marker interface in salt-function-flow; `ContextBus.putException()` downgrades such exceptions to DEBUG:

```java
// FlowControlException.java (salt-function-flow)
public interface FlowControlException {}

// ContextBus.java
public void putException(String nodeId, Exception e) {
    if (e instanceof FlowControlException) {
        log.debug("node flow control signal. nodeId:{}, type:{}",
                nodeId, e.getClass().getSimpleName());
    } else {
        log.warn("node exception. nodeId:{}, exception:", nodeId, e);
    }
    nodeExceptionMap.put(nodeId, e);
}

// AgentStoppedException.java (j-langchain)
public class AgentStoppedException extends RuntimeException
        implements FlowControlException { ... }
```

---

## 7. ToolMessage Reconstruction Issue

During implementation a deeper issue was found that affects resume correctness: **the LLM's tool-call response is sent back to the API with the wrong role**.

### 7.1 Root Cause

`ToolMessage` (the LLM's `AIMessage` carrying tool_calls) has a default role of `"tool"`. `BaseChatModel.convertMessage()` dispatches on role via a switch:

```
TOOL → AiChatInput.Message(role="tool", content, name, toolCallId)
```

This constructs the LLM's tool-call response as a "tool result message", silently discarding the `toolCalls` field. The API receives:

```
user: question
tool: (empty content, no tool_call_id)        ← wrong: should be assistant + tool_calls
tool[search_city]: 成都: 查询完成
tool[get_hotel]: 成都: 查询完成
```

The LLM does not recognise these tool results as its own prior output and calls the tools again, causing an infinite loop.

### 7.2 Fix

Differentiate the two message types in the `TOOL` branch of `convertMessage()`:
- `ToolMessage.toolCalls` non-empty → LLM's response: map to `assistant` role with a `tool_calls` array
- `ToolMessage.toolCallId` non-null → tool result: keep `tool` role

```java
case TOOL:
    if (baseMessage instanceof ToolMessage tm && !CollectionUtils.isEmpty(tm.getToolCalls())) {
        AiChatInput.Message assistantMsg =
            new AiChatInput.Message(RoleType.ASSISTANT.getCode(),
                                    tm.getContent() != null ? tm.getContent() : "");
        assistantMsg.setToolCalls(tm.getToolCalls().stream().map(tc -> {
            AiChatInput.ToolCall itc = new AiChatInput.ToolCall();
            itc.setId(tc.getId());
            itc.setType(tc.getType());
            if (tc.getFunction() != null) {
                AiChatInput.ToolCall.FunctionCall fc = new AiChatInput.ToolCall.FunctionCall();
                fc.setName(tc.getFunction().getName());
                fc.setArguments(tc.getFunction().getArguments());
                itc.setFunction(fc);
            }
            return itc;
        }).collect(Collectors.toList()));
        messages.add(assistantMsg);
    } else {
        messages.add(new AiChatInput.Message(RoleType.TOOL.getCode(),
            baseMessage.getContent(), ((ToolMessage) baseMessage).getName(),
            ((ToolMessage) baseMessage).getToolCallId()));
    }
    break;
```

Correct API message sequence:

```
user: question
assistant: (tool_calls: [{search_city, {city:成都}}, {get_hotel, {city:成都}}])
tool[search_city]: 成都: 查询完成
tool[get_hotel]: 成都: 查询完成
```

---

## 8. End-to-End Data Flow

```
────────── Normal execution ──────────────────────────────────────────

agent.invoke(question)
  │
  ├─ create stopSignal = new AtomicBoolean(false)
  ├─ transmitMap: STOP_SIGNAL → stopSignal
  │
  ▼
initContext node
  ├─ PRELOADED_CTX absent → create new AgentTaskContext
  └─ store in AGENT_TASK_CTX

applyPreloadedSteps node
  └─ completedSteps empty → pass through prompt output unchanged

loop (shouldContinue → LLM → executeTool)
  ├─ shouldContinue: signal.get() == false → continue
  ├─ LLM call
  ├─ executeTool: run tool → ctx.addStep() → buildChatPromptValue()
  └─ repeat until Final Answer

────────── stop() called ─────────────────────────────────────────────

master.stop() ──→ stopSignal.set(true)

[after a tool completes]
shouldContinue: signal.get() == true
  └─ throw AgentStoppedException(ctx)
       │
       ▼
invoke() catches
  ├─ conversationStorer.savePartial(ctx)   [optional]
  └─ rethrow → caller receives ExecutionException.getCause()

────────── resume: continue execution ────────────────────────────────

partialCtx = stoppedException.getPartialContext()
agent.invoke(question, partialCtx)
  │
  ├─ create new stopSignal (can be stopped again)
  ├─ transmitMap: PRELOADED_CTX → partialCtx
  │
  ▼
initContext node
  └─ PRELOADED_CTX present → reuse partialCtx directly

applyPreloadedSteps node
  └─ completedSteps non-empty → replace with ctx.buildChatPromptValue()
       [LLM first call sees: user + assistant(tool_calls) + tool[...]]

loop continues from existing steps → Final Answer
```

---

## 9. invoke() Overload Design

Both `McpAgentExecutor` and `AgentExecutor` expose four `invoke` overloads, all delegating to a single core method:

```java
// Core entry point
public ChatGeneration invoke(String input, AtomicBoolean externalSignal,
                              AgentTaskContext preloadedCtx)

// Standard call
public ChatGeneration invoke(Object input)               // → invoke(str, null, null)
public ChatGeneration invoke(String input)               // → invoke(input, null, null)

// Skill/SubAgent: propagate parent's stop signal
public ChatGeneration invoke(String input,
                              AtomicBoolean externalSignal) // → invoke(input, signal, null)

// Resume: caller supplies prior context; can still be stopped again
public ChatGeneration invoke(String input,
                              AgentTaskContext preloadedCtx) // → invoke(input, null, ctx)
```

`externalSignal` and `preloadedCtx` are orthogonal parameters and can be combined (e.g. second-stop-after-resume scenarios).

---

## 10. Files Changed

### j-langchain

```
core/
  agent/
    AgentStoppedException.java    # New: carries partialContext; implements FlowControlException
    McpAgentExecutor.java         # New: stop(), invoke overloads, shouldContinue check,
                                  #      applyPreloadedSteps node, AgentStoppedException
                                  #      priority catch in executeTool, formatChatPromptValue
                                  #      enhanced with tool_calls display
    AgentExecutor.java            # Same changes as McpAgentExecutor (ReAct mode)
    memory/
      AgentTaskContext.java       # New default getCompletedSteps()
      AgentContext.java           # New default createWithSteps()
      FullContext.java            # Implements getCompletedSteps()
      SlidingWindowContext.java   # Implements getCompletedSteps()
  history/memory/
    ConversationMemoryStorerBase  # New savePartial() no-op (subclasses may override)
  subagent/
    SubAgent.java                 # invoke() propagates parent stopSignal
  skill/
    Skill.java                    # invoke() propagates parent stopSignal
  common/
    CallInfo.java                 # New enum values: STOP_SIGNAL, PRELOADED_CTX
  llm/
    BaseChatModel.java            # convertMessage() TOOL branch: distinguish LLM response
                                  # from tool result messages
```

### salt-function-flow

```
FlowControlException.java         # New: marker interface separating control-flow exceptions
                                  # from real errors
context/
  ContextBus.java                 # putException(): downgrade FlowControlException to DEBUG
```

---

## 11. Usage Examples

### 11.1 Basic Stop

```java
McpAgentExecutor agent = McpAgentExecutor.builder(chainActor)
    .llm(llm)
    .tools(searchTool)
    .build();

CompletableFuture<ChatGeneration> future = CompletableFuture.supplyAsync(
    () -> agent.invoke("Query travel info for Chengdu, Xi'an and Guilin"));

Thread.sleep(500);
agent.stop();   // called from any thread

try {
    future.get();
} catch (ExecutionException e) {
    AgentStoppedException stopped = (AgentStoppedException) e.getCause();
    System.out.println("Steps completed: " + stopped.getCompletedSteps().size());
}
```

### 11.2 Resume Execution

```java
AgentStoppedException stopped = ...;
AgentTaskContext partialCtx = stopped.getPartialContext();

// Resume the same question from where it stopped (can be stopped again)
ChatGeneration result = agent.invoke(question, partialCtx);
```

### 11.3 Inject Prior Steps into a New Instruction

```java
List<AgentStep> priorSteps = stopped.getCompletedSteps();
FullContext context = FullContext.build();
AgentTaskContext newCtx = context.createWithSteps(newQuestion, null, priorSteps);

ChatGeneration result = agent.invoke(newQuestion, newCtx);
```

### 11.4 SubAgent Stop Propagation

```java
McpAgentExecutor master = McpAgentExecutor.builder(chainActor)
    .llm(masterLlm)
    .subAgent(researcher)    // the SubAgent's loop also observes master.stop()
    .build();

master.stop();               // signal automatically propagates into the SubAgent
```

---

## 12. Design Principles

| Principle | How It Is Applied |
|-----------|-------------------|
| **Cross-thread safety** | `AtomicBoolean` guarantees visibility between `stop()` and `shouldContinue()` across threads without relying on ThreadLocal |
| **Minimal invasiveness** | No `if-stop` branches in the agent's main execution path; stop checks are concentrated in a single `shouldContinue` location |
| **Transparent resumption** | The agent does not know whether it is a fresh start or a resume; the difference is handled at the chain level by `initContext` and `applyPreloadedSteps` |
| **One-way signal propagation** | SubAgents and Skills actively read and forward the signal from ContextBus; there is no reverse coupling back to the parent |
| **Control flow vs. errors** | The `FlowControlException` marker interface lets the framework distinguish expected interrupts from real errors, preventing false-alarm log noise |
| **Step integrity** | The check point is after tool completion, not inside the tool; steps that have finished executing are never truncated |
| **Re-stoppable after resume** | A new `stopSignal` is created on each `invoke()`, so a resumed execution can be stopped again independently |

---

## 13. Runtime Stop Type Extensions

This section documents the new exception hierarchy and three runtime stop mechanisms added on top of the existing stop/resume design.

### 13.1 Exception Hierarchy

```
AgentException (abstract, implements FlowControlException)
├── AgentStoppedException   — unchanged; external user cancel; carries partialContext
├── AgentAbortException     — system-forced termination
│     reason: AgentAbortReason enum { MAX_STEPS, TIMEOUT, CONSECUTIVE_TOOL_FAILURES, BUDGET_EXCEEDED }
│     fields: AgentAbortReason reason, AgentTaskContext partialContext
└── AgentPauseException     — agent-initiated pause (upper-layer semantics)
      fields: String reason, Map<String,Object> payload, AgentTaskContext partialContext
```

**Design rationale**:

- `AgentStoppedException`: external interrupt (user calls `stop()`); resumable.
- `AgentAbortException`: a system limit was hit; the framework decides to abort. `MAX_STEPS` replaces the raw `RuntimeException` that previously existed at line 466. All variants carry `partialContext` for diagnostics.
- `AgentPauseException`: the agent semantically pauses (e.g., needs user input or approval). `reason` and `payload` are application-defined; the framework only provides the mechanism (save context, propagate). `partialContext` is saved for resumption.

All three new exception types implement `FlowControlException` via `AgentException`, so the flow engine logs them at DEBUG level rather than WARN.

### 13.2 Three New Runtime Stop Mechanisms

#### 13.2.1 TIMEOUT

Checked inside `shouldContinue`. When `i == 0`, `startTimeMs` is recorded. On every subsequent check, if elapsed time exceeds `maxDurationMs`, an `AgentAbortException(TIMEOUT)` is thrown.

Builder parameter: `.maxDurationSeconds(int)` — `0` disables the check.

#### 13.2.2 CONSECUTIVE_TOOL_FAILURES

An `AtomicInteger consecutiveFailures` is maintained inside the `executeTool` closure:
- Incremented on any tool failure (exception thrown or tool not found).
- Reset to `0` on a successful tool call.
- When it reaches `maxConsecFail`, an `AgentAbortException(CONSECUTIVE_TOOL_FAILURES)` is thrown.

**Important**: this counter tracks LLM-driven behavior — how many consecutive rounds the LLM keeps invoking failing tools — not the number of framework-level retries.

Builder parameter: `.maxConsecutiveToolFailures(int)` — `0` disables the check.

#### 13.2.3 Framework Tool Retry (toolRetry)

Before an error observation is handed back to the LLM, the framework silently retries the tool call up to `toolRetryCount` times. Only if every attempt fails does the error reach the LLM as an observation. This process is transparent to the LLM.

Builder parameter: `.toolRetry(int)` — `0` means no retry.

### 13.3 Key Design Principles

- **CONSECUTIVE_TOOL_FAILURES is not the same as toolRetry.** The former measures LLM behavior (how many rounds the LLM keeps calling failing tools). The latter (`toolRetry`) is the framework retrying before the LLM is even aware of the failure. They are independent and controlled separately.
- **AgentPauseException is intentionally thin.** The framework defines the mechanism (save context, propagate the exception); it does not define the semantics (what "wait_user" means is entirely up to the application layer).
- All three new exception types implement `FlowControlException` via `AgentException`, so the framework logs them at DEBUG level and no false-alarm warnings are generated.

### 13.4 invoke() Catch Blocks

Both `McpAgentExecutor` and `AgentExecutor` now catch all three exception types:

```java
catch (AgentStoppedException e) { savePartial() + rethrow }
catch (AgentPauseException e)   { savePartial() + rethrow }
catch (AgentAbortException e)   { savePartial() + rethrow }
```

### 13.5 New Builder Methods

```java
.maxDurationSeconds(int)          // 0 = disable timeout check
.maxConsecutiveToolFailures(int)  // 0 = disable consecutive-failure check
.toolRetry(int)                   // 0 = no retry
```

### 13.6 Files Changed

```
core/agent/
  AgentException.java               # NEW: abstract base; implements FlowControlException
  AgentAbortReason.java             # NEW: enum { MAX_STEPS, TIMEOUT, CONSECUTIVE_TOOL_FAILURES, BUDGET_EXCEEDED }
  AgentAbortException.java          # NEW: system-forced termination exception
  AgentPauseException.java          # NEW: agent-initiated pause exception
  AgentStoppedException.java        # MODIFIED: now extends AgentException
  McpAgentExecutor.java             # MODIFIED: new builder params, shouldContinue timeout check,
                                    #   executeTool retry + consecutive-failure counter, invoke catch blocks
  AgentExecutor.java                # MODIFIED: invoke now catches AgentPauseException and AgentAbortException
```
