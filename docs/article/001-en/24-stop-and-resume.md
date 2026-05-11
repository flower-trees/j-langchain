# Agent Stop and Resume: Interruptible Long-Running Tasks

> **Tags**: `Java` `stop` `resume` `AgentStoppedException` `partialContext` `interruptible` `j-langchain`  
> **Prerequisite**: [SubAgent Advanced: LLM Strategies, Tool Borrowing, and Skill Nesting](23-subagent-advanced.md)  
> **Audience**: Java developers building Agent systems that need controllable interruption and resumption

---

## 1. The Problem: Uncontrollable Long-Running Agents

An Agent querying travel information across multiple cities may call tools a dozen times and take tens of seconds. During that window:

- The user changed their mind and wants to cancel
- An upstream timeout fires and the task must terminate immediately
- The task is halfway done and the user wants to adjust the goal and continue

The traditional approach — let the thread run to completion or brutally `interrupt()` it — either wastes resources or loses all progress made so far.

j-langchain provides a **controlled stop-and-resume mechanism**:
- `agent.stop()` sends a stop signal; the Agent halts at the next safe checkpoint
- `AgentStoppedException` carries a `partialContext` containing all completed tool-call steps
- `agent.invoke(question, partialContext)` resumes from the interruption point, skipping already-completed steps
- `context.createWithSteps()` injects prior steps into a new instruction — "change direction while keeping your progress"

---

## 2. Stop Mechanism Overview

```
Main thread                     Agent execution thread
  │                                  │
  │  CompletableFuture.supplyAsync() │
  │─────────────────────────────────>│
  │                                  │ tool invoked (slow)
  │  toolStarted.await()             │   tool running...
  │<─ latch.countDown()              │
  │                                  │
  │  agent.stop()                    │
  │──── set stopSignal = true ──────>│
  │                                  │ tool returns → check shouldContinue()
  │                                  │   stopSignal=true → halt
  │                                  │   throw AgentStoppedException
  │<─────────────────────────────────│
  │  future.get() throws ExecutionException
  │  getCause() → AgentStoppedException
  │  getPartialContext() → completed steps
```

The Agent does **not** interrupt a tool mid-execution. It waits for the current tool call to return, then checks the stop signal before entering the next LLM reasoning round. This guarantees tool-call atomicity — a tool either executes completely or not at all.

---

## 3. Basic Stop: Triggering AgentStoppedException

```java
CountDownLatch toolStarted = new CountDownLatch(1);

McpAgentExecutor agent = McpAgentExecutor.builder(chainActor)
        .llm(ChatAliyun.builder().model("qwen-plus").temperature(0f).build())
        .tools(slowTool("search_city", "Query city travel info", toolStarted, 500))
        .verbose(true)
        .build();

// Run Agent asynchronously
CompletableFuture<ChatGeneration> future = CompletableFuture.supplyAsync(
        () -> agent.invoke("Query travel info for Chengdu, Xi'an, and Guilin in sequence"));

// Stop after the tool starts executing
toolStarted.await(10, TimeUnit.SECONDS);
agent.stop();

try {
    future.get(15, TimeUnit.SECONDS);
    Assert.fail("Expected AgentStoppedException");
} catch (ExecutionException e) {
    Assert.assertTrue(e.getCause() instanceof AgentStoppedException);
    System.out.println("[stop succeeded] cause: " + e.getCause().getMessage());
}
```

`slowTool` triggers `latch.countDown()` when invoked, then `sleep(500ms)` before returning. The test thread calls `agent.stop()` immediately after the latch fires, ensuring the stop signal is set before the `shouldContinue()` check that follows the tool's return.

---

## 4. partialContext: Preserving Completed Steps

`AgentStoppedException` is not a plain exception — it carries all completed tool-call steps from before the halt:

```java
} catch (ExecutionException e) {
    Assert.assertTrue(e.getCause() instanceof AgentStoppedException);
    AgentStoppedException stopped = (AgentStoppedException) e.getCause();

    // partialContext contains the full execution context
    Assert.assertNotNull("partialContext must not be null", stopped.getPartialContext());

    // completedSteps is the list of fully completed tool-call steps
    List<AgentStep> steps = stopped.getCompletedSteps();
    Assert.assertNotNull("completedSteps must not be null", steps);

    System.out.println("[completed steps] " + steps.size());
    // Example output: [completed steps] 1
    // step 1: the result of search_city("Chengdu") is saved
}
```

Structure of `AgentStep`:

```
AgentStep
  ├── action (tool call request: tool name + arguments)
  └── observation (tool return value)
```

Each complete "tool call + result" pair forms one `AgentStep`. Completed steps are saved in `partialContext` at stop time and can be reused on the next `invoke()` to avoid re-executing them.

---

## 5. Stop Signal Propagates into SubAgent

When the master Agent uses a SubAgent, `master.stop()` cascades the stop signal through `ContextBus` into the SubAgent's inner execution loop:

```java
SubAgentConfig config = new SubAgentConfig();
config.setName("travel_researcher");
config.setDescription("Travel researcher — queries travel info for a given city");

CountDownLatch subToolStarted = new CountDownLatch(1);
SubAgent researcher = SubAgent.from(config, chainActor)
        .llm(ChatAliyun.builder().model("qwen-plus").temperature(0f).build())
        .tools(slowTool("search_city", "Query city travel info", subToolStarted, 500))
        .build();

McpAgentExecutor master = McpAgentExecutor.builder(chainActor)
        .llm(ChatAliyun.builder().model("qwen-plus").temperature(0f).build())
        .subAgent(researcher)
        .systemPrompt("You are a travel assistant. Use travel_researcher for travel info queries.")
        .verbose(true)
        .build();

CompletableFuture<ChatGeneration> future = CompletableFuture.supplyAsync(
        () -> master.invoke("Get travel info for Chengdu"));

subToolStarted.await(10, TimeUnit.SECONDS);
master.stop(); // stop master; signal propagates to SubAgent via ContextBus

try {
    future.get(15, TimeUnit.SECONDS);
    Assert.fail("Expected AgentStoppedException");
} catch (ExecutionException e) {
    Assert.assertTrue("stop signal should propagate from SubAgent to Master",
            e.getCause() instanceof AgentStoppedException);
    System.out.println("[SubAgent stop propagation succeeded]");
}
```

Signal propagation chain:

```
master.stop()
  │
  └── ContextBus.transmit["STOP_SIGNAL"] = true
                │
                ▼
        SubAgent.invoke() checks ContextBus
                │
                └── executor.invoke(input, parentSignal)
                        SubAgent inner executor checks shouldContinue()
                        → false → throw AgentStoppedException
```

Regardless of how deeply nested the call chain is (Master → SubAgent → Skill → embedded SubAgent), the stop signal propagates layer by layer through `ContextBus`. The entire chain halts synchronously — no partial-stop, partial-continue situations.

---

## 6. Resume with partialContext

After stopping, pass the `partialContext` from the `AgentStoppedException` to the next `invoke()`. The Agent will skip already-completed steps and continue from the interruption point:

```java
McpAgentExecutor agent = McpAgentExecutor.builder(chainActor)
        .llm(ChatAliyun.builder().model("qwen-plus").temperature(0f).build())
        .tools(
            slowTool("search_city", "Query city travel info", toolStarted, 400),
            fastTool("get_hotel",   "Query hotel prices")
        )
        .verbose(true)
        .build();

String question = "Query Chengdu travel info and hotel prices, then give recommendations";

// ── First run: stopped mid-execution ──────────────────────────────
CompletableFuture<ChatGeneration> firstRun = CompletableFuture.supplyAsync(
        () -> agent.invoke(question));

toolStarted.await(10, TimeUnit.SECONDS);
agent.stop();

AgentStoppedException stopped = null;
try {
    firstRun.get(15, TimeUnit.SECONDS);
} catch (ExecutionException e) {
    stopped = (AgentStoppedException) e.getCause();
}

System.out.println("[first run stopped, completed steps] " + stopped.getCompletedSteps().size());

// ── Second run: resume with partialContext ────────────────────────
AgentTaskContext partialCtx = stopped.getPartialContext();
ChatGeneration result = agent.invoke(question, partialCtx);  // pass prior context

Assert.assertFalse("resumed result must not be blank", result.getText().isBlank());
System.out.println("[resumed result]\n" + result.getText());
```

Execution comparison:

```
First run:
  Step 1: search_city("Chengdu") → "Chengdu travel info..."   ← completed
  [stop signal] halt, throw AgentStoppedException
  partialContext saves step 1

Second run (with partialContext):
  [skip step 1 — use cached result]
  Step 2: get_hotel("Chengdu") → "Chengdu: 3-star ¥280/night..." ← continue
  Final Answer: Chengdu travel recommendations...
```

Completed steps are not re-executed — saving LLM call count and tool invocation overhead.

---

## 7. createWithSteps: Inject Prior Steps into a New Instruction

Sometimes after a stop you don't want to repeat the original question — you want to change direction. But you also don't want to discard data already fetched. `createWithSteps()` lets you manually inject prior steps into a new instruction's context:

```java
FullContext context = FullContext.build();

McpAgentExecutor agent = McpAgentExecutor.builder(chainActor)
        .llm(ChatAliyun.builder().model("qwen-plus").temperature(0f).build())
        .context(context)   // inject the same context object; needed by createWithSteps later
        .tools(
            slowTool("search_city", "Query city travel info", toolStarted, 400),
            fastTool("get_hotel",   "Query hotel prices")
        )
        .verbose(true)
        .build();

// ── Stop first run, collect completed steps ──────────────────────
CompletableFuture<ChatGeneration> firstRun = CompletableFuture.supplyAsync(
        () -> agent.invoke("Query Chengdu travel and hotel info"));

toolStarted.await(10, TimeUnit.SECONDS);
agent.stop();

AgentStoppedException stopped = ...;
List<AgentStep> priorSteps = stopped.getCompletedSteps();
System.out.println("[prior steps] " + priorSteps.size());

// ── Inject prior steps into a new instruction ────────────────────
String newQuestion = "Based on the existing info, recommend Xi'an instead and check Xi'an hotels";
AgentTaskContext newCtx = context.createWithSteps(newQuestion, null, priorSteps);

ChatGeneration result = agent.invoke(newQuestion, newCtx);
System.out.println("[new instruction result]\n" + result.getText());
```

Semantics of `createWithSteps()`:

```
priorSteps (completed steps from the old task)
  + newQuestion (new task instruction)
  ↓
AgentTaskContext (new execution context)
  Agent continues in this context:
  - Old steps are passed to the LLM as "known information"
  - LLM answers the new question based on prior data — avoids re-querying
```

**When to use**: The user changes their goal mid-task, but already-fetched data (e.g., weather, flight info) remains relevant to the new goal.

---

## 8. Three Resumption Strategies Compared

| Strategy | API | When to use |
|----------|-----|-------------|
| Restart from scratch | `agent.invoke(question)` | Prior steps have no value; redo everything |
| Resume from checkpoint | `agent.invoke(question, partialCtx)` | Same question, continue — skip completed steps |
| New instruction with prior steps | `context.createWithSteps(newQ, null, priorSteps)` | Different question, but prior data is still valuable |

---

## 9. Prerequisites

1. **`ALIYUN_KEY`** environment variable — examples use `qwen-plus`
2. No classpath resource files needed — all tools are constructed in code
3. Stop tests depend on multi-thread timing; run in a non-resource-constrained environment

---

## 10. Summary

The Agent stop-and-resume mechanism solves the controllability problem for long-running Agents:

- **Controlled stop**: `agent.stop()` doesn't force-interrupt — it waits for the current tool to return, then halts at a safe checkpoint, preserving tool-call atomicity
- **Progress persistence**: `AgentStoppedException.getPartialContext()` carries all completed steps — interrupt means save
- **Signal cascading**: Stop signal propagates from master Agent into SubAgents via `ContextBus` — the entire call chain halts synchronously
- **Three resumption strategies**: restart, resume from checkpoint, new instruction with prior steps — covering different business scenarios

In production, this mechanism enables many critical scenarios: user cancellation, timeout degradation, human review then continue, dynamic task goal adjustment — transforming a long-running Agent from an "uncontrollable black box" into a "pausable, resumable, redirectable controllable process."

---

> 📎 Resources
> - Full source: [Article24StopAndResume.java](../../../src/test/java/org/salt/jlangchain/demo/article/Article24StopAndResume.java)
> - j-langchain GitHub: https://github.com/flower-trees/j-langchain
> - Runtime: `ALIYUN_KEY` (`qwen-plus`)
