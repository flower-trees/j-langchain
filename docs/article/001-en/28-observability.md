# Agent Observability: Token Usage Stats and Execution Metrics

> **Tags**: `Java` `AiTokenUsage` `AgentExecutionMetrics` `onTokenUsage` `AgentTokenUsageEvent` `observability` `j-langchain`  
> **Prerequisites**: [AgentExecutor: Start a ReAct Agent with One Line](09-agent-executor.md)  
> **Audience**: Java developers who need to monitor AI application cost and performance

---

## 1. Why Observability Matters

Agent tasks typically involve multiple rounds of LLM calls and tool executions. Without observability you can't answer:

- How many tokens did this task consume? What did it cost?
- How much time was spent in LLM calls vs. tool calls?
- Among concurrent Agents, which one is the most expensive?

j-langchain 1.0.16 provides two orthogonal observability interfaces:

| Interface | When | Use case |
|-----------|------|----------|
| `onTokenUsage` callback | After each LLM round in real time | Live monitoring, streaming logs, cost alerts |
| `result.getResponseMetadata()` | Once after task completes | Aggregate stats, DB logging, test assertions |

---

## 2. Data Structures

### 2.1 AiTokenUsage — Token Usage

```java
public class AiTokenUsage {
    long promptTokens;      // Input tokens (cumulative across all rounds)
    long completionTokens;  // Output tokens
    long totalTokens;       // Total tokens
    long cachedTokens;      // KV-cache hits (supported by some vendors)
    long reasoningTokens;   // Thinking tokens for reasoning models
    long llmCalls;          // Number of LLM calls
    long toolCalls;         // Number of tool calls
    String provider;        // Vendor name
    String model;           // Model name
}
```

Read from task result:

```java
Object raw = result.getResponseMetadata().get(AiTokenUsage.METADATA_KEY);
AiTokenUsage usage = raw instanceof AiTokenUsage u ? u : null;
```

### 2.2 AgentExecutionMetrics — Execution Timing

```java
public class AgentExecutionMetrics {
    long startedAtMs;       // Task start timestamp
    long endedAtMs;         // Task end timestamp
    long durationMs;        // Total elapsed time (end-to-end)
    long llmDurationMs;     // Sum of all LLM call durations
    long toolDurationMs;    // Sum of all tool call durations
    long llmCalls;          // Number of LLM calls
    long toolCalls;         // Number of tool calls
}
```

Read from task result:

```java
Object raw = result.getResponseMetadata().get(AgentExecutionMetrics.METADATA_KEY);
AgentExecutionMetrics metrics = raw instanceof AgentExecutionMetrics m ? m : null;
```

### 2.3 AgentTokenUsageEvent — Per-Round Event

After each LLM call the `onTokenUsage` callback receives this event:

```java
public class AgentTokenUsageEvent {
    String taskId;                // Task ID
    AiTokenUsage deltaUsage;      // This-round incremental usage
    AiTokenUsage totalUsage;      // Cumulative usage for the task so far
    long deltaDurationMs;         // This-round elapsed time (including tools)
    long totalDurationMs;         // Cumulative task elapsed time
    long llmDurationMs;           // This-round LLM call duration
    long toolDurationMs;          // This-round tool call duration
    long llmCalls;                // LLM calls this round (usually 1)
    long toolCalls;               // Tool calls this round
}
```

---

## 3. Registering an onTokenUsage Callback

Call `.onTokenUsage(consumer)` in the `McpAgentExecutor.builder()` chain:

```java
McpAgentExecutor agent = McpAgentExecutor.builder(chainActor)
        .llm(ChatAliyun.builder().model("qwen-plus").build())
        .tools(weatherTool, timeTool)
        .onTokenUsage(event -> {
            System.out.println("round tokens: " + event.getDeltaUsage().getTotalTokens()
                    + ", cumulative: " + event.getTotalUsage().getTotalTokens()
                    + ", LLM ms: " + event.getLlmDurationMs());
        })
        .build();
```

The callback is invoked **synchronously on the Agent's execution thread**. It's suitable for writing to logs or pushing to monitoring systems; for async processing, submit to a thread pool inside the callback.

---

## 4. Reading Aggregate Data After Task Completion

No special builder config is needed — `AiTokenUsage` and `AgentExecutionMetrics` are always injected into the result metadata after `invoke()` completes:

```java
ChatGeneration result = agent.invoke("What's the weather and time in Shanghai?");

// ── Token usage ───────────────────────────────────────────────────────────
AiTokenUsage usage = (AiTokenUsage) result.getResponseMetadata()
        .get(AiTokenUsage.METADATA_KEY);
System.out.println("total tokens: " + usage.getTotalTokens()
        + ", LLM calls: " + usage.getLlmCalls());

// ── Execution timing ──────────────────────────────────────────────────────
AgentExecutionMetrics metrics = (AgentExecutionMetrics) result.getResponseMetadata()
        .get(AgentExecutionMetrics.METADATA_KEY);
System.out.println("total: " + metrics.getDurationMs() + "ms"
        + ", LLM: " + metrics.getLlmDurationMs() + "ms"
        + ", tools: " + metrics.getToolDurationMs() + "ms");
```

---

## 5. AgentExecutor Support

`AgentExecutor` (ReAct-style) also supports `onTokenUsage` and metadata reading with an identical API:

```java
AgentExecutor agent = AgentExecutor.builder(chainActor)
        .llm(ChatAliyun.builder().model("qwen-plus").build())
        .tools(weatherTool)
        .onTokenUsage(event -> log.info("token event: {}", event))
        .build();

ChatGeneration result = agent.invoke("Beijing weather?");
AiTokenUsage usage = (AiTokenUsage) result.getResponseMetadata()
        .get(AiTokenUsage.METADATA_KEY);
```

---

## 6. Full Examples

### 6.1 Real-time Callback (monitoring push)

```java
McpAgentExecutor agent = McpAgentExecutor.builder(chainActor)
        .llm(qwen())
        .tools(weatherTool(), timeTool())
        .onTokenUsage(event -> {
            // Push to Prometheus, InfluxDB, or Grafana
            metricsRegistry.record(
                event.getDeltaUsage().getTotalTokens(),
                event.getLlmDurationMs()
            );
        })
        .build();

agent.invoke("Check weather in Beijing and Shanghai");
```

### 6.2 Post-task Aggregate (DB logging)

```java
ChatGeneration result = agent.invoke("Check weather and time in Shanghai");

AiTokenUsage usage = tokenUsage(result);
AgentExecutionMetrics metrics = executionMetrics(result);

// Write to database
taskLog.record(TaskLog.builder()
        .totalTokens(usage.getTotalTokens())
        .cost(estimateCost(usage))
        .durationMs(metrics.getDurationMs())
        .llmMs(metrics.getLlmDurationMs())
        .toolMs(metrics.getToolDurationMs())
        .build());
```

---

## 7. Prerequisites

1. **`ALIYUN_KEY`** environment variable: examples use `qwen-plus`
2. All tools are in-memory mocks — no external dependencies
3. All 4 test methods can run independently

---

## 8. Summary

| Need | Recommended interface |
|------|-----------------------|
| Real-time notification after each LLM call | `onTokenUsage(consumer)` |
| Token aggregate after task completes | `result.getResponseMetadata().get(AiTokenUsage.METADATA_KEY)` |
| Timing breakdown after task completes | `result.getResponseMetadata().get(AgentExecutionMetrics.METADATA_KEY)` |
| Both real-time and aggregate | Both can be used simultaneously, no conflict |

Both interfaces require zero additional configuration, work in both `McpAgentExecutor` and `AgentExecutor`, and slot directly into existing monitoring pipelines.

---

> Related resources
> - Full code: [Article28Observability.java](../../../src/test/java/org/salt/jlangchain/demo/article/Article28Observability.java)
> - j-langchain GitHub: https://github.com/flower-trees/j-langchain
> - Runtime requirement: `ALIYUN_KEY` (`qwen-plus`)
