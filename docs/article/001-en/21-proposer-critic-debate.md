# Proposer-Critic Multi-Round Debate: Two LLM Agents Converging on Consensus with `loop()`

> **Tags**: `Java` `loop` `Proposer-Critic` `multi-round debate` `consensus` `pure LLM` `no tools`  
> **Prerequisite**: [Dual-Agent Self-Correcting Code Generation: Write Agent + Test Agent Driving `loop()`](20-two-agent-self-correct.md)  
> **Audience**: Developers who know `loop()` basics and want the lightest-weight multi-agent debate pattern in Java

---

## 1. The Problem: Why Debate?

A single LLM generating a proposal has a well-known blind spot: **it doesn't spontaneously challenge its own output**. Once the LLM produces an answer, it tends toward self-affirmation rather than self-criticism.

The Proposer-Critic pattern breaks this limitation through **role separation**:

- **Proposer**: focuses on creation — drafts proposals or revises based on feedback
- **Critic**: focuses on fault-finding — identifies weaknesses or approves the final proposal
- **`loop()`**: coordinates the two agents through repeated iterations until the Critic is satisfied or the maximum number of rounds is reached

Unlike the previous article's dual-agent code correction, neither agent here **needs any tools** — pure LLM calls only. This is the lightest-weight form of the `loop()` multi-agent pattern.

---

## 2. Architecture

```
User topic ("Design a distributed task scheduling system...")
     ↓
TranslateHandler (save topic to ContextBus transmitMap)
     ↓
┌────────────────────────────────────────────────────────────┐
│  loop(condition, proposerNode, criticNode)   max 5 rounds   │
│                                                            │
│  proposerNode (Proposer Agent)                             │
│    Pure LLM call (PromptTemplate + ChatAliyun)             │
│    Round 1: generate initial proposal → write to transmitMap│
│    Round 2+: read last critique → revise proposal → write  │
│                                                            │
│  criticNode (Critic Agent)                                 │
│    Pure LLM call (PromptTemplate + ChatAliyun)             │
│    Every round: read proposal → output [CRITIQUE]/[APPROVED]│
│    On [APPROVED]: write consensus=true                     │
└────────────────────────────────────────────────────────────┘
     ↓
TranslateHandler (format final proposal)
     ↓
Final proposal + status (✅ Consensus reached / ⚠️ Max rounds reached)
```

Only four keys in the shared state — each with a clear purpose:

| Key | Written by | Read by | Purpose |
|-----|-----------|---------|---------|
| `topic` | Pre-loop TranslateHandler | Proposer / Critic (every round) | Preserve the original topic |
| `proposal` | proposerNode lambda | criticNode lambda | Current proposal text |
| `critique` | criticNode lambda | proposerNode lambda (next round) | Last round's critique |
| `consensus` | criticNode lambda | loop condition | `"true"` terminates the loop |

---

## 3. The Two Agents

Both agents are plain LLM call chains — no `McpAgentExecutor`, no tools of any kind.

### Proposer

```java
FlowInstance proposerFlow = chainActor.builder()
    .next(PromptTemplate.fromTemplate("${prompt}"))
    .next(ChatAliyun.builder().model("deepseek-v4-flash").temperature(0.7f).build())
    .next(new StrOutputParser())
    .build();
```

Higher `temperature(0.7f)` encourages the Proposer to generate more creative proposals. The trailing `StrOutputParser` wraps the LLM's `AIMessage` into a `ChatGeneration` so the lambda can call `.getText()` directly.

### Critic

```java
FlowInstance criticFlow = chainActor.builder()
    .next(PromptTemplate.fromTemplate("${prompt}"))
    .next(ChatAliyun.builder().model("deepseek-v4-flash").temperature(0.3f).build())
    .next(new StrOutputParser())
    .build();
```

Lower `temperature(0.3f)` keeps the Critic's assessments stable, strict, and consistent.

> The two agents can use different models: a cost-effective model for the Proposer, a stronger reasoning model for the Critic.

---

## 4. Consensus Signal Design

The Critic's prompt requires it to output exactly one of two formats:

```
[APPROVED] <reason for approval>
[CRITIQUE] <list of issues>
```

The criticNode lambda uses `startsWith` to parse the response and writes structured state — **the loop condition reads a single boolean flag, never parses LLM prose**:

```java
if (text.startsWith("[APPROVED]")) {
    ContextBus.get().putTransmit("consensus", "true");
} else {
    ContextBus.get().putTransmit("critique", text);
}
```

This design is robust: swapping the Critic for a more verbose model doesn't break the loop logic. The structured prefix is the only judgment criterion.

---

## 5. The Loop Logic

```java
.loop(
    // condition: continue if no consensus yet and under the round limit
    i -> {
        boolean approved = "true".equals(ContextBus.get().getTransmit("consensus"));
        boolean cont = !approved && i < 5;
        if (i > 0) {
            System.out.printf("%n--- Round %d check: approved=%b, continue=%b ---%n",
                i + 1, approved, cont);
        }
        return cont;
    },

    // Node A: Proposer — round 1 drafts initial proposal; later rounds revise based on critique
    (Object input) -> {
        String topic    = ContextBus.get().getTransmit("topic");
        String critique = ContextBus.get().getTransmit("critique");
        String proposal = ContextBus.get().getTransmit("proposal");

        String prompt = (critique == null)
            ? "You are a senior architect. Propose a technical solution (under 300 words) for: \n" + topic
            : "You are a senior architect. Your previous proposal:\n" + proposal +
              "\n\nReview feedback:\n" + critique +
              "\n\nRevise and improve your proposal (under 300 words). Output only the revised proposal body.";

        ChatGeneration result = chainActor.invoke(proposerFlow, Map.of("prompt", prompt));
        ContextBus.get().putTransmit("proposal", result.getText());
        return result;
    },

    // Node B: Critic — reviews proposal, outputs [APPROVED] or [CRITIQUE]
    (Object ignored) -> {
        String topic    = ContextBus.get().getTransmit("topic");
        String proposal = ContextBus.get().getTransmit("proposal");

        String prompt = "You are a strict technical reviewer. Topic: " + topic +
                        "\n\nCurrent proposal:\n" + proposal +
                        "\n\n- If the proposal is sufficiently complete, reply starting with [APPROVED], explain why (under 100 words)." +
                        "\n- If there are clear flaws, reply starting with [CRITIQUE], list the issues (under 100 words), do NOT suggest fixes.";

        ChatGeneration result = chainActor.invoke(criticFlow, Map.of("prompt", prompt));
        String text = result.getText();
        if (text.startsWith("[APPROVED]")) {
            ContextBus.get().putTransmit("consensus", "true");
        } else {
            ContextBus.get().putTransmit("critique", text);
        }
        return result;
    }
)
```

---

## 6. Execution Trace

```
========== Proposer-Critic Multi-Round Debate ==========
Topic: Design a high-concurrency distributed task scheduling system supporting millions of tasks, second-level latency, and auto-recovery from failures

--- Round 1: Proposer drafts initial proposal ---
[Proposer]
Master-Worker architecture: the Master node handles task dispatch and state management;
Worker nodes process tasks from a pool. Tasks are stored in a Redis Sorted Set (sorted by
execution time); the Master polls for due tasks and dispatches to idle Workers. ZooKeeper
handles Master leader election — a standby node takes over within 10 seconds on failure.
Workers send heartbeats every 5 seconds; timed-out Workers are evicted and their tasks
are re-queued. Scale horizontally by adding Workers.

[Critic]
[CRITIQUE]
1. Redis single-node polling is a throughput bottleneck — the Master becomes the bottleneck
   at millions-of-tasks scale.
2. No deduplication mechanism for re-queued tasks; Worker failure can cause duplicate execution.
3. ZooKeeper leader-election failover of 10 seconds violates the "second-level latency" requirement.

--- Round 2 check: approved=false, continue=true ---

--- Proposer revises proposal ---
[Proposer]
Sharded Master + Worker architecture: tasks are hashed by taskId to multiple Master shards,
eliminating the single-node bottleneck. Tasks are stored in a sharded Redis Cluster; each
Master shard polls independently — peak throughput scales linearly. An idempotency key
(taskId + execution version) is introduced; Workers perform a CAS claim before execution
to prevent duplicate runs. Master HA switches to Raft (etcd) — leader election < 1 second,
meeting the second-level latency requirement. Worker heartbeat every 3 seconds; lost tasks
are safely retried by other Workers via idempotent CAS.

[Critic - Consensus reached]
[APPROVED] The proposal fully addresses all three issues: the sharded architecture eliminates
the Master bottleneck, idempotent CAS ensures exactly-once execution, and etcd Raft
leader election meets the second-level cutover requirement. The design is production-viable.

--- Round 3 check: approved=true, continue=false ---

========== Final Proposal ==========
Status: ✅ Consensus reached

Sharded Master + Worker architecture: tasks are hashed by taskId to multiple Master shards...
====================================
```

Two rounds to consensus. The round-1 proposal had three flaws; the Proposer fixed all three in round 2 and the Critic approved. If round 2 still had issues, the loop would continue up to 5 rounds before force-stopping.

---

## 7. Comparison with Dual-Agent Code Correction

| Dimension | Dual-agent code correction (Article 20) | Proposer-Critic debate (this article) |
|-----------|----------------------------------------|---------------------------------------|
| Agent tools | Write Agent: MCP filesystem; Test Agent: compile_and_run | Both agents: no tools |
| Termination signal | `test_result` startsWith("PASS") | `consensus` == "true" |
| Signal source | Tool function writes directly | Critic LLM output `[APPROVED]` prefix |
| Use case | Tasks requiring real execution validation | Pure text generation and review tasks |
| Setup complexity | High (MCP + custom tools) | Minimal (two plain LLM flows) |

---

## 8. Prerequisites

1. **`ALIYUN_KEY`** environment variable: example uses `deepseek-v4-flash`
2. No Node.js, no JDK toolchain, no MCP configuration needed

---

## 9. Summary

This article demonstrated the lightest-weight multi-agent debate pattern in j-langchain:

- **No tools, pure LLM**: both agents are just `PromptTemplate + ChatAliyun` — zero tool dependencies
- **Structured consensus signal**: the Critic outputs `[APPROVED]`/`[CRITIQUE]` prefixes; the loop condition reads a single boolean flag — no natural language parsing
- **Temperature differentiation**: higher temperature for the Proposer encourages creativity; lower temperature for the Critic enforces consistency
- **`loop()` as coordinator**: alternating execution, shared state reads/writes, and termination logic are all managed by `loop()`

This pattern generalizes to any "generate → review → improve" scenario: copywriting optimization, architecture review, code review (when real compilation isn't needed), requirements clarification, and more.

---

> 📎 Resources
> - Full example: [Article21ProposerCriticDebate.java](../../../src/test/java/org/salt/jlangchain/demo/article/Article21ProposerCriticDebate.java), method `proposerCriticDebate()`
> - j-langchain GitHub: https://github.com/flower-trees/j-langchain
> - Runtime requirements: `ALIYUN_KEY` (`deepseek-v4-flash`)
