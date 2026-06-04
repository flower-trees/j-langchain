# Agent 可观测性：Token 用量统计与执行指标

> **标签**：`Java` `AiTokenUsage` `AgentExecutionMetrics` `onTokenUsage` `AgentTokenUsageEvent` `可观测性` `j-langchain`  
> **前置阅读**：[AgentExecutor：用一行代码启动 ReAct Agent](09-agent-executor.md)  
> **适合人群**：需要监控 AI 应用成本与性能的 Java 开发者

---

## 一、为什么需要可观测性

Agent 任务通常需要多轮 LLM 调用和多次工具执行。没有可观测性，你无从判断：

- 一次任务花了多少 Token？对应多少费用？
- LLM 调用占了多少时间？工具调用拖慢了哪个环节？
- 多个并发 Agent 中，哪一个消耗最多资源？

j-langchain 1.0.16 提供两个正交的可观测性接口：

| 接口 | 时机 | 场景 |
|------|------|------|
| `onTokenUsage` 回调 | 每轮 LLM 调用后实时触发 | 实时监控、流式日志、成本预警 |
| `result.getResponseMetadata()` | 任务完成后一次性读取 | 汇总统计、日志落库、测试断言 |

---

## 二、数据结构

### 2.1 AiTokenUsage — Token 用量

```java
public class AiTokenUsage {
    long promptTokens;      // 输入 token 数（所有轮次累计）
    long completionTokens;  // 输出 token 数
    long totalTokens;       // 总 token 数
    long cachedTokens;      // 命中 KV 缓存的 token 数（部分厂商支持）
    long reasoningTokens;   // 推理模型的 thinking token 数
    long llmCalls;          // LLM 调用次数
    long toolCalls;         // 工具调用次数
    String provider;        // 厂商名
    String model;           // 模型名
}
```

从任务结果中读取：

```java
Object raw = result.getResponseMetadata().get(AiTokenUsage.METADATA_KEY);
AiTokenUsage usage = raw instanceof AiTokenUsage u ? u : null;
```

### 2.2 AgentExecutionMetrics — 执行耗时

```java
public class AgentExecutionMetrics {
    long startedAtMs;       // 任务开始时间戳
    long endedAtMs;         // 任务结束时间戳
    long durationMs;        // 总耗时（端到端）
    long llmDurationMs;     // 所有 LLM 调用耗时之和
    long toolDurationMs;    // 所有工具调用耗时之和
    long llmCalls;          // LLM 调用次数
    long toolCalls;         // 工具调用次数
}
```

从任务结果中读取：

```java
Object raw = result.getResponseMetadata().get(AgentExecutionMetrics.METADATA_KEY);
AgentExecutionMetrics metrics = raw instanceof AgentExecutionMetrics m ? m : null;
```

### 2.3 AgentTokenUsageEvent — 实时事件

每轮 LLM 调用完成后，`onTokenUsage` 回调收到此事件：

```java
public class AgentTokenUsageEvent {
    String taskId;                // 任务 ID
    AiTokenUsage deltaUsage;      // 本轮增量用量
    AiTokenUsage totalUsage;      // 任务累计用量
    long deltaDurationMs;         // 本轮耗时（含工具）
    long totalDurationMs;         // 任务累计耗时
    long llmDurationMs;           // 本轮 LLM 调用耗时
    long toolDurationMs;          // 本轮工具调用耗时
    long llmCalls;                // 本轮 LLM 调用次数（通常为 1）
    long toolCalls;               // 本轮工具调用次数
}
```

---

## 三、注册 onTokenUsage 回调

在 `McpAgentExecutor.builder()` 链上调用 `.onTokenUsage(consumer)`：

```java
McpAgentExecutor agent = McpAgentExecutor.builder(chainActor)
        .llm(ChatAliyun.builder().model("qwen-plus").build())
        .tools(weatherTool, timeTool)
        .onTokenUsage(event -> {
            System.out.println("本轮 token: " + event.getDeltaUsage().getTotalTokens()
                    + "，累计: " + event.getTotalUsage().getTotalTokens()
                    + "，LLM 耗时: " + event.getLlmDurationMs() + "ms");
        })
        .build();
```

回调在 **Agent 执行线程**中同步调用，适合写入日志、推送监控系统；若需要异步处理，请在回调内自行提交到线程池。

---

## 四、从任务结果读取汇总数据

无需任何 Builder 配置，invoke() 完成后 `AiTokenUsage` 和 `AgentExecutionMetrics` 始终注入到元数据中：

```java
ChatGeneration result = agent.invoke("上海天气和时间分别是什么？");

// ── Token 用量 ────────────────────────────────────────────────────────────
AiTokenUsage usage = (AiTokenUsage) result.getResponseMetadata()
        .get(AiTokenUsage.METADATA_KEY);
System.out.println("总 token: " + usage.getTotalTokens()
        + "，LLM 调用次数: " + usage.getLlmCalls());

// ── 执行耗时 ──────────────────────────────────────────────────────────────
AgentExecutionMetrics metrics = (AgentExecutionMetrics) result.getResponseMetadata()
        .get(AgentExecutionMetrics.METADATA_KEY);
System.out.println("总耗时: " + metrics.getDurationMs() + "ms"
        + "，LLM: " + metrics.getLlmDurationMs() + "ms"
        + "，工具: " + metrics.getToolDurationMs() + "ms");
```

---

## 五、AgentExecutor 的用法

`AgentExecutor`（ReAct 风格）同样支持 `onTokenUsage` 和元数据读取，接口完全一致：

```java
AgentExecutor agent = AgentExecutor.builder(chainActor)
        .llm(ChatAliyun.builder().model("qwen-plus").build())
        .tools(weatherTool)
        .onTokenUsage(event -> log.info("token event: {}", event))
        .build();

ChatGeneration result = agent.invoke("北京天气？");
AiTokenUsage usage = (AiTokenUsage) result.getResponseMetadata()
        .get(AiTokenUsage.METADATA_KEY);
```

---

## 六、完整用例

### 6.1 实时回调（监控推送场景）

```java
McpAgentExecutor agent = McpAgentExecutor.builder(chainActor)
        .llm(qwen())
        .tools(weatherTool(), timeTool())
        .onTokenUsage(event -> {
            // 可推送至 Prometheus、InfluxDB 或 Grafana
            metricsRegistry.record(
                event.getDeltaUsage().getTotalTokens(),
                event.getLlmDurationMs()
            );
        })
        .build();

agent.invoke("查询北京和上海的天气");
```

### 6.2 任务结束后汇总（日志落库场景）

```java
ChatGeneration result = agent.invoke("分别查一下上海的天气和时间");

AiTokenUsage usage = tokenUsage(result);
AgentExecutionMetrics metrics = executionMetrics(result);

// 写入数据库
taskLog.record(TaskLog.builder()
        .totalTokens(usage.getTotalTokens())
        .cost(estimateCost(usage))
        .durationMs(metrics.getDurationMs())
        .llmMs(metrics.getLlmDurationMs())
        .toolMs(metrics.getToolDurationMs())
        .build());
```

---

## 七、运行前置条件

1. **`ALIYUN_KEY`** 环境变量：示例使用 `qwen-plus`
2. 工具均为内存模拟实现，无需外部依赖
3. 4 个测试方法均可独立运行

---

## 八、总结

| 需求 | 推荐接口 |
|------|---------|
| 每轮 LLM 调用后实时通知 | `onTokenUsage(consumer)` |
| 任务结束后读取 token 汇总 | `result.getResponseMetadata().get(AiTokenUsage.METADATA_KEY)` |
| 任务结束后读取耗时分布 | `result.getResponseMetadata().get(AgentExecutionMetrics.METADATA_KEY)` |
| 同时需要实时 + 汇总 | 两者可同时使用，互不影响 |

两种接口均无需额外配置，`McpAgentExecutor` 和 `AgentExecutor` 均支持，直接接入现有监控体系即可。

---

> 相关资源
> - 完整代码：[Article28Observability.java](../../../src/test/java/org/salt/jlangchain/demo/article/Article28Observability.java)
> - j-langchain GitHub：https://github.com/flower-trees/j-langchain
> - 运行环境：`ALIYUN_KEY`（`qwen-plus`）
