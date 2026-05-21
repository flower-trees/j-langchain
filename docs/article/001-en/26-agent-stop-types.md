# Agent Stop Types: MAX_STEPS, TIMEOUT, CONSECUTIVE_TOOL_FAILURES, and Pause-Resume

> **Tags**: `Java` `AgentAbortException` `AgentPauseException` `AgentStoppedException` `MAX_STEPS` `TIMEOUT` `CONSECUTIVE_TOOL_FAILURES` `toolRetry` `j-langchain`  
> **Prerequisite**: [Agent Stop and Resume: Interruptible Long-Running Tasks](24-stop-and-resume.md)  
> **Audience**: Java developers who need fine-grained control over agent termination, retry, and human-in-the-loop pausing

---

## 1. The Problem: One Stop Mechanism Is Not Enough

The stop-and-resume mechanism introduced in Article 24 handles one case: a user calls `agent.stop()` while the agent is running. That covers user cancellation — but production agents face a wider range of termination scenarios:

- The LLM enters a loop and keeps calling tools indefinitely — you need a step cap
- A tool hangs or the network stalls — you need a wall-clock timeout
- An external API is broken and the LLM keeps retrying it — you need a consecutive-failure guard
- A transient network blip causes one tool call to fail — you do **not** want the LLM to see the error at all; the framework should retry silently
- A financial tool exceeds an approval threshold mid-run — you need a structured way to pause, get human sign-off, and resume

j-langchain addresses all of these through a unified exception hierarchy and three new builder options.

---

## 2. The Exception Hierarchy

All agent loop termination signals extend a single abstract base class:

```
AgentException  (abstract, extends RuntimeException, implements FlowControlException)
├── AgentStoppedException   — external stop() call; carries partialContext
├── AgentAbortException     — system limit reached; carries AgentAbortReason + partialContext
│     Reasons: MAX_STEPS / TIMEOUT / CONSECUTIVE_TOOL_FAILURES / BUDGET_EXCEEDED
└── AgentPauseException     — tool-initiated pause; carries reason string + payload + partialContext
```

Because `AgentException` implements `FlowControlException`, the underlying flow engine treats these as intentional control signals and logs them at DEBUG level rather than ERROR level — no false alarms in your log pipeline.

The three subtypes divide responsibility cleanly:

| Exception | Who throws it | Trigger |
|-----------|---------------|---------|
| `AgentStoppedException` | Framework | `agent.stop()` from an external thread |
| `AgentAbortException` | Framework | System limit exceeded (steps, time, failures) |
| `AgentPauseException` | Application tool | Business logic requires human intervention |

---

## 3. New Builder Methods

Three new options on `McpAgentExecutor.builder()` control the framework-level abort behavior:

```java
McpAgentExecutor agent = McpAgentExecutor.builder(chainActor)
        .llm(...)
        .tools(...)
        .maxDurationSeconds(30)          // wall-clock timeout; 0 = no limit (default)
        .maxConsecutiveToolFailures(3)   // abort after 3 consecutive all-fail rounds; 0 = no limit (default)
        .toolRetry(2)                    // framework silently retries up to 2 times before surfacing error to LLM; 0 = no retry (default)
        .build();
```

The existing `.maxIterations(int)` (default: 10) is unchanged and still triggers `AgentAbortException(MAX_STEPS)` when the loop count is reached.

---

## 4. Stop Type 1 — MAX_STEPS

The agent loop has a hard ceiling on how many LLM reasoning rounds it will execute. When that ceiling is hit and the agent has not yet produced a final answer, the framework throws `AgentAbortException` with reason `MAX_STEPS`.

**Scenario**: ask for weather in four cities with `maxIterations(1)`. The LLM can only query one city before the ceiling is reached.

```java
Tool weatherTool = Tool.builder()
        .name("get_weather")
        .description("Query weather for a given city")
        .params("city: String")
        .func(args -> {
            @SuppressWarnings("unchecked")
            String city = ((Map<String, Object>) args).getOrDefault("city", "unknown").toString();
            return city + ": sunny, 26°C";
        })
        .build();

McpAgentExecutor agent = McpAgentExecutor.builder(chainActor)
        .llm(ChatAliyun.builder().model("qwen-plus").temperature(0f).build())
        .tools(weatherTool)
        .maxIterations(1)
        .verbose(true)
        .build();

try {
    agent.invoke("Query weather for Beijing, Shanghai, Guangzhou, and Shenzhen in sequence, then summarize");
    Assert.fail("AgentAbortException expected");
} catch (AgentAbortException e) {
    Assert.assertEquals(AgentAbortReason.MAX_STEPS, e.getReason());
    System.out.println("[testMaxSteps] reason=" + e.getReason()
            + "  steps=" + e.getCompletedSteps().size()
            + "  msg=" + e.getMessage());
}
```

`getCompletedSteps()` returns the steps that completed before the ceiling was hit, so you can inspect partial progress without losing it.

---

## 5. Stop Type 2 — TIMEOUT

When `.maxDurationSeconds(n)` is set and the wall clock exceeds `n` seconds after the agent starts, the framework checks the elapsed time at each `shouldContinue()` checkpoint (after every tool call returns) and throws `AgentAbortException(TIMEOUT)`.

The agent does **not** interrupt a tool mid-execution. It waits for the current tool to return, then aborts — preserving tool-call atomicity, consistent with the stop behavior described in Article 24.

**Scenario**: a tool sleeps for 4 seconds; the timeout is 1 second.

```java
Tool slowTool = Tool.builder()
        .name("slow_search")
        .description("Perform a time-consuming search")
        .params("query: String")
        .func(args -> {
            try { Thread.sleep(4000); } catch (InterruptedException ignored) {}
            return "search complete";
        })
        .build();

McpAgentExecutor agent = McpAgentExecutor.builder(chainActor)
        .llm(ChatAliyun.builder().model("qwen-plus").temperature(0f).build())
        .tools(slowTool)
        .maxDurationSeconds(1)
        .verbose(true)
        .build();

try {
    agent.invoke("Search for some information");
    Assert.fail("AgentAbortException expected");
} catch (AgentAbortException e) {
    Assert.assertEquals(AgentAbortReason.TIMEOUT, e.getReason());
    System.out.println("[testTimeout] reason=" + e.getReason()
            + "  msg=" + e.getMessage());
}
```

---

## 6. Stop Type 3 — CONSECUTIVE_TOOL_FAILURES

When every tool call in a round fails, the framework increments a consecutive-failure counter. If that counter reaches the configured limit, the framework throws `AgentAbortException(CONSECUTIVE_TOOL_FAILURES)`.

This guard prevents the LLM from spinning indefinitely on a broken downstream dependency.

**Scenario**: the tool always throws a `RuntimeException`; `maxConsecutiveToolFailures(1)` aborts after the first failed round.

```java
Tool failingTool = Tool.builder()
        .name("unstable_api")
        .description("Call an unstable external API")
        .params("query: String")
        .func(args -> {
            throw new RuntimeException("connection timeout — cannot reach API");
        })
        .build();

McpAgentExecutor agent = McpAgentExecutor.builder(chainActor)
        .llm(ChatAliyun.builder().model("qwen-plus").temperature(0f).build())
        .tools(failingTool)
        .maxConsecutiveToolFailures(1)
        .verbose(true)
        .build();

try {
    agent.invoke("Call the API to fetch the latest data");
    Assert.fail("AgentAbortException expected");
} catch (AgentAbortException e) {
    Assert.assertEquals(AgentAbortReason.CONSECUTIVE_TOOL_FAILURES, e.getReason());
    System.out.println("[testConsecutiveToolFailures] reason=" + e.getReason()
            + "  steps=" + e.getCompletedSteps().size()
            + "  msg=" + e.getMessage());
}
```

**Important distinction — `CONSECUTIVE_TOOL_FAILURES` vs `toolRetry`**:

- `toolRetry` acts **before** the failure counter. The framework retries the tool call silently up to `toolRetry` times. Only if all retries are exhausted does the error become an observation that the LLM sees — and only then does the failure counter increment.
- `CONSECUTIVE_TOOL_FAILURES` counts LLM-visible failures — rounds where the LLM received an error observation and then called the failing tool again.

In short: `toolRetry` filters out transient blips; `CONSECUTIVE_TOOL_FAILURES` catches sustained breakage that the LLM cannot recover from on its own.

---

## 7. Tool-Level Retry: Transparent to the LLM

When `.toolRetry(n)` is set, the framework retries a failing tool call up to `n` times before returning an error observation to the LLM. If any retry succeeds, the LLM receives only the successful result — it never sees the intermediate failures.

**Scenario**: the tool fails on attempts 1 and 2, succeeds on attempt 3; `toolRetry(2)` makes this transparent.

```java
AtomicInteger callCount = new AtomicInteger(0);

Tool flakyTool = Tool.builder()
        .name("flaky_api")
        .description("Fetch data (occasional network jitter)")
        .params("query: String")
        .func(args -> {
            int n = callCount.incrementAndGet();
            if (n <= 2) throw new RuntimeException("network jitter, attempt=" + n);
            return "query successful, data returned normally";
        })
        .build();

McpAgentExecutor agent = McpAgentExecutor.builder(chainActor)
        .llm(ChatAliyun.builder().model("qwen-plus").temperature(0f).build())
        .tools(flakyTool)
        .toolRetry(2)
        .verbose(true)
        .build();

var result = agent.invoke("Fetch the latest data via flaky_api");

Assert.assertNotNull(result);
Assert.assertFalse(result.getText().isBlank());
Assert.assertEquals("tool should be called 3 times (2 failures + 1 success)", 3, callCount.get());
System.out.println("[testToolRetry] callCount=" + callCount.get()
        + "  result=" + result.getText());
```

Retry timeline:

```
LLM calls flaky_api
  ├── attempt 1: RuntimeException("network jitter, attempt=1")  — framework retries
  ├── attempt 2: RuntimeException("network jitter, attempt=2")  — framework retries
  └── attempt 3: "query successful, data returned normally"      — framework returns to LLM
LLM receives: "query successful, data returned normally"
LLM never sees the two failures.
```

---

## 8. Stop Type 4 — AgentPauseException: Human-in-the-Loop

`AgentPauseException` is different from the other stop types: it is thrown by **application code inside a tool**, not by the framework. It signals that the agent needs external input — a human decision, an approval workflow, a second-factor confirmation — before it can continue.

The framework propagates the exception upward unchanged, preserving the `partialContext` so the caller can resume after obtaining approval.

**Scenario**: a transfer tool detects that the requested amount exceeds the auto-approval limit. It reads the current `AgentTaskContext` from `ContextBus`, then throws `AgentPauseException` with reason `"need_approval"` and a payload carrying the transfer details.

### Step 1 — Tool throws AgentPauseException

```java
Tool transferTool = Tool.builder()
        .name("transfer_money")
        .description("Execute a fund transfer")
        .params("amount: String, to: String")
        .func(args -> {
            @SuppressWarnings("unchecked")
            Map<String, Object> map = (Map<String, Object>) args;

            // Read the current execution context from ContextBus
            AgentTaskContext ctx = ContextBus.get().getTransmit(CallInfo.AGENT_TASK_CTX.name());

            // Amount exceeds the auto-approval limit — pause and request human approval
            throw new AgentPauseException(
                    "need_approval",
                    Map.of("amount", map.getOrDefault("amount", ""),
                           "to",     map.getOrDefault("to", ""),
                           "reason", "single transfer exceeds auto-approval limit"),
                    ctx);
        })
        .build();
```

### Step 2 — Caller catches the exception

```java
McpAgentExecutor agent = McpAgentExecutor.builder(chainActor)
        .llm(ChatAliyun.builder().model("qwen-plus").temperature(0f).build())
        .tools(transferTool)
        .verbose(true)
        .build();

AgentPauseException paused = null;
try {
    agent.invoke("Transfer 50000 yuan to Zhang San");
    Assert.fail("AgentPauseException expected");
} catch (AgentPauseException e) {
    paused = e;
    Assert.assertEquals("need_approval", e.getReason());
    Assert.assertFalse("payload must not be empty", e.getPayload().isEmpty());
    Assert.assertNotNull("partialContext must not be null", e.getPartialContext());
    System.out.println("[pause] reason=" + e.getReason()
            + "  payload=" + e.getPayload());
}
```

### Step 3 — Human approves; resume with partialContext

After approval, swap in a version of the tool that no longer throws and pass the saved `partialContext` to a new `invoke()`:

```java
Tool approvedTransferTool = Tool.builder()
        .name("transfer_money")
        .description("Execute a fund transfer (approved)")
        .params("amount: String, to: String")
        .func(args -> {
            @SuppressWarnings("unchecked")
            Map<String, Object> map = (Map<String, Object>) args;
            return "transfer successful: sent "
                    + map.getOrDefault("amount", "") + " yuan to "
                    + map.getOrDefault("to", "");
        })
        .build();

McpAgentExecutor approvedAgent = McpAgentExecutor.builder(chainActor)
        .llm(ChatAliyun.builder().model("qwen-plus").temperature(0f).build())
        .tools(approvedTransferTool)
        .verbose(true)
        .build();

AgentTaskContext savedCtx = paused.getPartialContext();
var result = approvedAgent.invoke("Transfer 50000 yuan to Zhang San", savedCtx);

Assert.assertFalse(result.getText().isBlank());
System.out.println("[resume result]\n" + result.getText());
```

Pause-and-resume flow:

```
First invoke
  LLM → calls transfer_money(amount=50000, to=ZhangSan)
    tool reads AgentTaskContext from ContextBus
    throw AgentPauseException("need_approval", {amount, to, reason}, ctx)
  Framework propagates exception to caller
  Caller: inspect payload, route to approval workflow

Approval granted

Second invoke (with savedCtx)
  [skip completed steps from savedCtx]
  LLM → calls transfer_money(amount=50000, to=ZhangSan)  — approved tool
    returns "transfer successful: ..."
  Final answer: transfer completed
```

The key pattern is that the tool reads `AgentTaskContext` from `ContextBus` at the moment it decides to pause, capturing all steps completed up to that point. The caller stores this context, presents the payload to the approval system, and hands it back to the next `invoke()` call — the same resume mechanism used in Article 24.

---

## 9. Comparison: All Stop Types at a Glance

| Stop type | Exception | Thrown by | Trigger | Carries partialContext |
|-----------|-----------|-----------|---------|----------------------|
| User cancel | `AgentStoppedException` | Framework | `agent.stop()` from external thread | Yes |
| Step limit | `AgentAbortException(MAX_STEPS)` | Framework | Loop count >= `maxIterations` | Yes |
| Wall-clock timeout | `AgentAbortException(TIMEOUT)` | Framework | Elapsed time > `maxDurationSeconds` | Yes |
| Sustained tool failures | `AgentAbortException(CONSECUTIVE_TOOL_FAILURES)` | Framework | Consecutive all-fail rounds >= `maxConsecutiveToolFailures` | Yes |
| Transient tool failure | _(no exception)_ | Framework | Retried silently up to `toolRetry` times, then error passed to LLM | N/A |
| Human-in-the-loop | `AgentPauseException` | Application tool | Business logic in tool body | Yes |

All framework-thrown exceptions check the stop condition **after** the current tool call returns — tool-call atomicity is always preserved.

---

## 10. Prerequisites

1. **`ALIYUN_KEY`** environment variable — examples use `qwen-plus`
2. No classpath resource files needed — all tools are constructed in-code
3. `testTimeout` depends on wall-clock timing; run in an environment where a 4-second sleep reliably exceeds a 1-second deadline

---

## 11. Summary

j-langchain's agent stop mechanism now covers the full production spectrum:

- **`MAX_STEPS`** — caps loop depth; prevents infinite reasoning chains
- **`TIMEOUT`** — caps wall-clock duration; enables SLA enforcement and graceful degradation
- **`CONSECUTIVE_TOOL_FAILURES`** — detects sustained downstream breakage that the LLM cannot self-recover from
- **`toolRetry`** — absorbs transient blips at the framework level; LLM sees only eventual success or final failure
- **`AgentPauseException`** — gives application code a structured, resumable way to inject human decisions into a running agent

Every stop type except `toolRetry` preserves a `partialContext` for inspection or resumption, using the same `agent.invoke(question, partialContext)` mechanism described in Article 24. The exception hierarchy is unified under `AgentException`, so a single catch clause can handle all cases when needed, or individual catch blocks can handle each type precisely.

In production these primitives combine naturally: set `maxIterations` + `maxDurationSeconds` as a safety net, `toolRetry` to absorb flaky dependencies, `maxConsecutiveToolFailures` to detect permanently broken ones, and `AgentPauseException` wherever human judgment is required — giving you a fully controllable, observable, resumable agent execution model.

---

> Resources
> - Full source: [Article26AgentStopTypes.java](../../../src/test/java/org/salt/jlangchain/demo/article/Article26AgentStopTypes.java)
> - j-langchain GitHub: https://github.com/flower-trees/j-langchain
> - Runtime: `ALIYUN_KEY` (`qwen-plus`)
