# Human-in-the-Loop: Agent Pauses for User Confirmation

> **Tags**: `Java` `AgentPauseException` `Human-in-the-Loop` `tool semantic split` `LLM routing` `partialContext` `addHumanTurn` `j-langchain`  
> **Prerequisite**: [Agent Runtime Stop Types: MAX_STEPS, TIMEOUT, CONSECUTIVE_TOOL_FAILURES and AgentPauseException](26-agent-stop-types.md)  
> **Audience**: Java developers who need to insert human approval checkpoints into an otherwise autonomous agent execution flow

---

## 1. The Problem: Fully Autonomous Is Not Always Correct

The core value of an agent is **autonomous execution**: the user states an intent, and the agent calls tools to complete the task. But "fully autonomous" is the wrong default for a class of operations that are irreversible by nature:

| Operation | Irreversibility | Why human confirmation is needed |
|-----------|----------------|----------------------------------|
| Bank transfer | Very high | Funds are difficult to recover once sent |
| Bulk file deletion | High | Overwritten or misdeleted files cannot be restored |
| Bulk database update | High | Live data corruption is extremely costly to fix |
| Sending emails / notifications | Medium | Cannot be recalled once sent; affects others |
| Production deployment | High | A wrong version impacts live users |

A naive approach is to hardcode "return at a certain point, wait for external re-invocation with new parameters" in the application layer. This tightly couples the pause logic with the agent's reasoning loop, is expensive to change, and cannot handle "the agent has already completed several steps and should not repeat them" gracefully.

j-langchain provides a cleaner solution: **tool semantic split + AgentPauseException + partialContext step-skip resumption**.

---

## 2. Design: Tool Semantic Split

The central idea is to decompose a "dangerous operation" into three tools with distinct, unambiguous semantics:

```
request_transfer   →   initiate the request (always pauses, waits for human confirmation)
confirm_transfer   →   execute the transfer (called by LLM after the user approves)
cancel_transfer    →   abort the operation  (called by LLM after the user declines)
```

Together with an information-gathering tool (`check_balance`), the complete flow uses four tools:

| Tool | Pauses? | Responsibility |
|------|---------|----------------|
| `check_balance` | No | Auto-execute; gather data needed for the decision |
| `request_transfer` | **Yes** | Initiate the request; throw `AgentPauseException` with confirmation payload |
| `confirm_transfer` | No | Called by LLM after user approves; performs the real operation |
| `cancel_transfer` | No | Called by LLM after user declines; cleans up safely |

The key insight is: **the framework does not need to know the business logic of "approve or decline"**. The user's decision is appended as a new human turn via `addHumanTurn`, and the LLM routes to the appropriate tool autonomously based on the full conversation context.

---

## 3. Full Flow Diagram

```
User: "Check my balance first, then transfer 50000 yuan to Zhang San if sufficient"
          │
          ▼
┌──────────────────────────────────────────────────────────────────┐
│  First invoke(question)                                          │
│                                                                  │
│  LLM reasoning → calls check_balance(account=...)               │
│    ✓ returns: "Balance: ¥80,000 — sufficient"                    │
│    → step-1 recorded into partialContext                         │
│                                                                  │
│  LLM reasoning → calls request_transfer(amount=50000, to=ZhangSan)│
│    ! tool throws AgentPauseException(                            │
│           reason="need_confirmation",                            │
│           payload={"action": "Transfer ¥50000 to Zhang San"},    │
│           ctx=current execution context)                         │
│    Framework records step-2 with synthetic observation           │
│    "[paused: need_confirmation]", then propagates exception      │
└──────────────────────────────────────────────────────────────────┘
          │
          ▼  caller catches AgentPauseException
          │
          │  Display to user:
          │    Action: Transfer ¥50000 to Zhang San
          │    Completed steps so far: 2
          │      (step-1: balance check; step-2: transfer request recorded)
          │
          ▼  user inputs y or n
          │
┌──────────────────────────────────────────────────────────────────┐
│  Second invoke("y" or "n",                                       │
│                paused.getPartialContext())                        │
│                                                                  │
│  Framework appends user input as a new human turn (addHumanTurn) │
│  LLM sees the full, chronologically-correct message chain:       │
│    human:     original question                                  │
│    assistant: check_balance(...)                                 │
│    tool:      Balance: ¥80,000 — sufficient                      │
│    assistant: request_transfer(...)                              │
│    tool:      [paused: need_confirmation]                        │
│    human:     y   ← user decision, after the pause point         │
│                                                                  │
│  User decision = "y"                  User decision = "n"        │
│    ↓                                    ↓                        │
│  calls confirm_transfer               calls cancel_transfer      │
│    → "Transfer successful:              → "Transfer cancelled:   │
│        sent ¥50000 to Zhang San"            user declined"       │
└──────────────────────────────────────────────────────────────────┘
```

---

## 4. Tool Design in Detail

### 4.1 check_balance: auto-execute, no confirmation needed

```java
Tool balanceTool = Tool.builder()
        .name("check_balance")
        .description("Query the current account balance")
        .params("account: String")
        .func(args -> "Balance: ¥80,000 — sufficient funds available")
        .build();
```

A plain tool that returns normally. The result is recorded as step-1. When resuming with `partialContext`, this step is skipped — **the balance is not queried a second time**.

---

### 4.2 request_transfer: initiate request, always pauses

```java
Tool requestTransferTool = Tool.builder()
        .name("request_transfer")
        .description("Initiate a transfer request; the system pauses and waits for user confirmation before executing")
        .params("amount: String, to: String")
        .func(args -> {
            @SuppressWarnings("unchecked")
            Map<String, Object> map = (Map<String, Object>) args;

            // Capture the current agent execution context from ContextBus
            AgentTaskContext ctx = ContextBus.get()
                    .getTransmit(CallInfo.AGENT_TASK_CTX.name());

            // Always pause — this tool's sole job is to request a pause
            throw new AgentPauseException(
                    "need_confirmation",
                    Map.of("action", "Transfer ¥" + map.get("amount") + " to " + map.get("to")),
                    ctx);
        })
        .build();
```

Key points:
- `ContextBus.get().getTransmit(CallInfo.AGENT_TASK_CTX.name())` captures the execution context **at this exact moment**, including all steps completed so far.
- `payload` is a structured description presented to the user; it can contain any business data.
- This tool **always throws** — its sole responsibility is to request a pause. It performs no actual business operation.
- **Framework behavior**: when `AgentPauseException` is caught, the framework records this tool call with a synthetic observation `"[paused: need_confirmation]"` in `partialContext`, ensuring the LLM sees the full call history on resume.

---

### 4.3 confirm_transfer: executes after user approves

```java
Tool confirmTransferTool = Tool.builder()
        .name("confirm_transfer")
        .description("Call this tool after the user has approved; performs the actual transfer")
        .params("amount: String, to: String")
        .func(args -> {
            @SuppressWarnings("unchecked")
            Map<String, Object> map = (Map<String, Object>) args;
            return "Transfer successful: sent ¥" + map.get("amount") + " to " + map.get("to");
        })
        .build();
```

This is the tool that performs the real operation. **The LLM will not choose it on the first invoke** — its description says "after the user has approved", which is a precondition the LLM respects. Only when the user inputs `y` as the new human turn on resume will the LLM route here.

---

### 4.4 cancel_transfer: cancels after user declines

```java
Tool cancelTransferTool = Tool.builder()
        .name("cancel_transfer")
        .description("Call this tool after the user has declined; cancels the transfer")
        .params("reason: String")
        .func(args -> "Transfer cancelled: user declined the operation")
        .build();
```

Symmetrically, when the user inputs `n` as the new human turn, the LLM routes here.

---

## 5. Complete Code

### 5.1 Build the agent

```java
McpAgentExecutor agent = McpAgentExecutor.builder(chainActor)
        .llm(ChatAliyun.builder().model("qwen-plus").temperature(0f).build())
        .tools(balanceTool, requestTransferTool, confirmTransferTool, cancelTransferTool)
        .verbose(true)
        .build();
```

A single agent instance containing all four tools. Unlike the `testPauseAndResume` pattern in Article 26 (which requires rebuilding the agent with a different tool after approval), here the **same** agent instance is used for resume — the tools do not change; the LLM routes based on context.

---

### 5.2 First invoke: run until the confirmation checkpoint

```java
String question = "Check my balance first, then transfer 50000 yuan to Zhang San if the balance is sufficient";

AgentPauseException paused = null;
try {
    agent.invoke(question);
} catch (AgentPauseException e) {
    paused = e;
}

// Display to user
System.out.println(">>> Confirmation required");
System.out.println("    Action: " + paused.getPayload().get("action"));
// Output: Action: Transfer ¥50000 to ZhangSan

System.out.println("    Completed steps so far: " + paused.getCompletedSteps().size());
// Output: Completed steps so far: 2
// step-1: check_balance call + "Balance: ¥80,000 — sufficient"
// step-2: request_transfer call + "[paused: need_confirmation]"
```

---

### 5.3 Collect user input and resume

```java
// Production: String userInput = new Scanner(System.in).nextLine();
String userInput = "y"; // simulated

// Pass the user's input directly — the framework appends it as a new human turn at the end
ChatGeneration result = agent.invoke(userInput, paused.getPartialContext());

System.out.println(">>> Result (user input: " + userInput + ")");
System.out.println(result.getText());
// y → Transfer successful: sent ¥50000 to Zhang San
// n → Transfer cancelled: user declined the operation
```

The message chain the framework builds for the LLM:

```
human:     Check my balance first, then transfer...  (original request)
assistant: [tool_call: check_balance]
tool:      Balance: ¥80,000 — sufficient
assistant: [tool_call: request_transfer]
tool:      [paused: need_confirmation]
human:     y                                          ← user decision, after the pause point
```

This order lets the LLM reason clearly: the transfer request was initiated and awaiting confirmation; the user has just decided to approve → call `confirm_transfer`.

---

### 5.4 Full test code (both branches)

```java
@Test
public void testUserConfirmYes() {
    doConfirmFlow("y");
}

@Test
public void testUserConfirmNo() {
    doConfirmFlow("n");
}

private void doConfirmFlow(String simulatedInput) {
    String question = "Check my balance first, then transfer 50000 yuan to Zhang San if the balance is sufficient";

    Tool balanceTool = Tool.builder()
            .name("check_balance")
            .description("Query the current account balance")
            .params("account: String")
            .func(args -> "Balance: ¥80,000 — sufficient funds available")
            .build();

    Tool requestTransferTool = Tool.builder()
            .name("request_transfer")
            .description("Initiate a transfer request; the system pauses and waits for user confirmation before executing")
            .params("amount: String, to: String")
            .func(args -> {
                @SuppressWarnings("unchecked")
                Map<String, Object> map = (Map<String, Object>) args;
                AgentTaskContext ctx = ContextBus.get()
                        .getTransmit(CallInfo.AGENT_TASK_CTX.name());
                throw new AgentPauseException(
                        "need_confirmation",
                        Map.of("action", "Transfer ¥" + map.get("amount") + " to " + map.get("to")),
                        ctx);
            })
            .build();

    Tool confirmTransferTool = Tool.builder()
            .name("confirm_transfer")
            .description("Call this tool after the user has approved; performs the actual transfer")
            .params("amount: String, to: String")
            .func(args -> {
                @SuppressWarnings("unchecked")
                Map<String, Object> map = (Map<String, Object>) args;
                return "Transfer successful: sent ¥" + map.get("amount") + " to " + map.get("to");
            })
            .build();

    Tool cancelTransferTool = Tool.builder()
            .name("cancel_transfer")
            .description("Call this tool after the user has declined; cancels the transfer")
            .params("reason: String")
            .func(args -> "Transfer cancelled: user declined the operation")
            .build();

    McpAgentExecutor agent = McpAgentExecutor.builder(chainActor)
            .llm(ChatAliyun.builder().model("qwen-plus").temperature(0f).build())
            .tools(balanceTool, requestTransferTool, confirmTransferTool, cancelTransferTool)
            .verbose(true)
            .build();

    // ── First invoke: check balance, then pause on transfer request ──────
    AgentPauseException paused = null;
    try {
        agent.invoke(question);
    } catch (AgentPauseException e) {
        paused = e;
    }

    System.out.println("\n>>> Confirmation required");
    System.out.println("    Action: " + paused.getPayload().get("action"));
    System.out.println("    Completed steps before pause: " + paused.getCompletedSteps().size());

    // ── Simulate user input; pass it directly as the new human turn ───────
    // Production: String userInput = new Scanner(System.in).nextLine();
    System.out.print("Proceed with the action above? (y/n): ");
    System.out.println(simulatedInput + "  ← simulated input");

    var result = agent.invoke(simulatedInput, paused.getPartialContext());

    System.out.println("\n>>> Result (user input: " + simulatedInput + ")");
    System.out.println(result.getText());
}
```

**Sample output — y branch**:

```
>>> Confirmation required
    Action: Transfer ¥50000 to ZhangSan
    Completed steps before pause: 2
Proceed with the action above? (y/n): y  ← simulated input

>>> Result (user input: y)
Transfer successful: sent ¥50000 to Zhang San
```

**Sample output — n branch**:

```
>>> Confirmation required
    Action: Transfer ¥50000 to ZhangSan
    Completed steps before pause: 2
Proceed with the action above? (y/n): n  ← simulated input

>>> Result (user input: n)
Transfer cancelled: user declined the operation
```

---

## 6. Why Let the LLM Route Instead of the Framework

A seemingly simpler design is to have the framework decide which tool to call on resume: if `userInput == "y"` call `confirm_transfer`, otherwise call `cancel_transfer`. This approach is intentionally **not used** for the following reasons.

### 6.1 The LLM understands semantics, not just y/n

Users may type:
- `"y, but change the amount to 30000"` → LLM calls `confirm_transfer` with the new amount
- `"check the recipient's account first, then confirm"` → LLM calls a lookup tool, then confirms
- `"n, let's do it tomorrow"` → LLM calls `cancel_transfer` with a reason

The framework cannot parse these; it can only do string comparison. The LLM understands intent and adjusts its subsequent actions accordingly.

### 6.2 Resume paths are not always binary

In real scenarios, there may be multiple post-resume paths: partial approval, parameter modification, request for additional information. Framework-level `if-else` logic grows quickly. Tool descriptions serve as the LLM's routing table — adding a new path means adding a new tool with a description. The framework never needs to know about it.

### 6.3 Cleaner separation of concerns

The framework has one job: **append the user's input as a new human turn, inject the completed steps from `partialContext` into the conversation history, and let the LLM continue reasoning**. Business routing logic lives entirely in the tool descriptions and the LLM's understanding. The framework stays free of business logic.

---

## 7. How partialContext and addHumanTurn Work Together

### 7.1 What partialContext carries

`AgentPauseException.getPartialContext()` returns an `AgentTaskContext` containing:

- **Completed step list** (`completedSteps`): each step includes the LLM's tool call and the tool's return value
- The paused tool's synthetic step with observation `"[paused: need_confirmation]"`

In this example, there are **2 steps** at pause time:
```
step-1: check_balance call + "Balance: ¥80,000 — sufficient"
step-2: request_transfer call + "[paused: need_confirmation]"
```

### 7.2 Message injection on resume

When `agent.invoke("y", savedCtx)` is called, the framework:
1. Loads the steps from `partialContext` and injects them into the conversation history
2. Appends `"y"` as the final human message via `addHumanTurn`
3. The LLM reasons from the complete, chronologically-correct context

**No step is repeated**: step-1 already contains the balance result; step-2 shows where the pause occurred. The user's decision `"y"` appears at the right point in time.

---

## 8. Extended Scenarios

The Human-in-the-Loop pattern applies to any irreversible operation, not just transfers.

### 8.1 Bulk file deletion

```java
// Scan tool: automatically list files to delete
Tool scanFilesTool = Tool.builder()
        .name("scan_files_to_delete")
        .description("Scan a directory and return the list of files to delete")
        .params("directory: String, pattern: String")
        .func(args -> "Found 47 .log files totaling 2.3 GB")
        .build();

// Request deletion tool: pause and wait for confirmation
Tool requestDeleteTool = Tool.builder()
        .name("request_batch_delete")
        .description("Initiate a batch delete request; system pauses to wait for user confirmation")
        .params("directory: String, pattern: String, count: String")
        .func(args -> {
            AgentTaskContext ctx = ContextBus.get()
                    .getTransmit(CallInfo.AGENT_TASK_CTX.name());
            throw new AgentPauseException(
                    "need_confirmation",
                    Map.of("action", "Delete " + ((Map<?,?>) args).get("count") + " log files"),
                    ctx);
        })
        .build();
```

### 8.2 Bulk database update

```java
// Preview tool: show the number of rows that will be affected
Tool previewUpdateTool = Tool.builder()
        .name("preview_bulk_update")
        .description("Preview a bulk update and return the number of affected rows")
        .params("table: String, condition: String, new_value: String")
        .func(args -> "Will update 1,234 rows")
        .build();

// Request update tool: pause and wait for DBA approval
Tool requestUpdateTool = Tool.builder()
        .name("request_bulk_update")
        .description("Initiate a bulk database update request; waits for DBA confirmation")
        .params("table: String, condition: String, new_value: String, affected_rows: String")
        .func(args -> {
            AgentTaskContext ctx = ContextBus.get()
                    .getTransmit(CallInfo.AGENT_TASK_CTX.name());
            throw new AgentPauseException("need_dba_approval",
                    Map.of("sql_preview", "UPDATE " + ((Map<?,?>) args).get("table") + "...",
                           "affected_rows", ((Map<?,?>) args).get("affected_rows")),
                    ctx);
        })
        .build();
```

### 8.3 Universal template

Every scenario follows the same structure:

```
Information-gathering tool (automatic)
    → Request-initiation tool (pauses)
    → Confirm-execution tool (automatic, called by LLM after user approves)
    → Cancel-operation tool (automatic, called by LLM after user declines)
```

The tool description language is the routing rule. The LLM reasons from context to determine which branch to take. Adding a new branch means adding a new tool — the framework changes nothing.

---

## 9. Prerequisites

1. **`ALIYUN_KEY`** environment variable — examples use `qwen-plus`
2. No classpath resource files needed — all tools are constructed in code
3. Both test methods (`testUserConfirmYes` / `testUserConfirmNo`) are ready to run as-is

---

## 10. Summary

The Human-in-the-Loop implementation in j-langchain reflects three design principles:

**Tool semantic split**: Decompose a "dangerous operation" into "initiate request", "confirm execution", and "cancel operation" — three tools with single, unambiguous responsibilities. The tool description itself becomes the LLM's routing rule.

**AgentPauseException carries the execution snapshot**: When the tool throws, the framework records the paused call with a synthetic `"[paused: ...]"` observation and saves it in `partialContext`. On resume, those steps are injected directly into the conversation history — the LLM sees them as already done, knows where the pause occurred, and does not repeat any work.

**addHumanTurn preserves chronological order**: The user's decision is appended as a new human turn at the end of the message chain, not prepended to the original question. This means the conversation history reads in the correct temporal order: original request → completed steps → user decision. The LLM makes its routing decision from this complete, time-ordered context. The framework contains no business logic.

Together, these three principles deliver the "agent autonomy + human oversight at critical checkpoints" collaboration model: the agent maximizes automation efficiency while never acting unilaterally on irreversible operations.

---

> Resources
> - Full source: [Article27HumanInTheLoop.java](../../../src/test/java/org/salt/jlangchain/demo/article/Article27HumanInTheLoop.java) (`testUserConfirmYes` / `testUserConfirmNo` / `doConfirmFlow` methods)
> - Prerequisite: [Agent Runtime Stop Types: MAX_STEPS, TIMEOUT, CONSECUTIVE_TOOL_FAILURES and AgentPauseException](26-agent-stop-types.md)
> - j-langchain GitHub: https://github.com/flower-trees/j-langchain
> - Runtime: `ALIYUN_KEY` (`qwen-plus`)
