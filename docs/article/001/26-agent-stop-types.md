# Agent 停止类型全解：Runtime 中止与语义暂停

> **标签**：`Java` `AgentAbortException` `AgentPauseException` `AgentStoppedException` `maxDurationSeconds` `toolRetry` `异常体系` `j-langchain`  
> **前置阅读**：[Agent 停止与恢复：可中断的长任务](24-stop-and-resume.md)  
> **适合人群**：需要对 Agent 运行时行为做精细控制的 Java 开发者

---

## 一、为什么需要更完整的停止体系

[前篇](24-stop-and-resume.md) 介绍了用户主动调用 `agent.stop()` 触发 `AgentStoppedException` 的机制。这覆盖了"用户取消"这一场景，但真实生产环境还面临更多问题：

- **Agent 陷入死循环**：LLM 反复调用工具却无法得到答案，消耗无限 Token
- **工具长时间不返回**：网络超时、外部服务挂起，整个 Agent 线程卡死
- **工具持续报错**：下游服务故障，LLM 一轮一轮重试失败工具，毫无意义
- **业务流程需要人工审核**：转账、发货等敏感操作，Agent 执行到关键节点必须暂停等待

j-langchain 1.0.16 在原有 `AgentStoppedException` 的基础上，补全了三类新的停止语义，构成完整的异常体系。

---

## 二、异常体系总览

```
FlowControlException（接口，框架流控标记）
└── AgentException（抽象基类）
      ├── AgentStoppedException   → 外部 stop()，user_cancel
      │     ├── getPartialContext()   已完成步骤的上下文快照
      │     └── getCompletedSteps()  已完成的 AgentStep 列表
      │
      ├── AgentAbortException     → 系统强制终止
      │     ├── getReason()           AbortReason 枚举
      │     │     MAX_STEPS               — 达到最大迭代次数
      │     │     TIMEOUT                 — 超过最大执行时长
      │     │     CONSECUTIVE_TOOL_FAILURES — LLM 连续调用失败工具超限
      │     │     BUDGET_EXCEEDED         — Token / 成本超限（预留）
      │     └── getCompletedSteps()  中止前已完成的步骤列表
      │
      └── AgentPauseException     → Agent 主动暂停（业务语义）
            ├── getReason()           暂停原因字符串，由业务定义
            ├── getPayload()          附带的业务数据 Map<String, Object>
            └── getPartialContext()   暂停时的上下文快照，供恢复使用
```

**两大类设计原则**：

| 类别 | 触发方 | 语义 | 可恢复 |
|------|--------|------|--------|
| Runtime 中止（`AgentAbortException`） | 框架自动检测 | "继续执行没有意义" | 不建议，应修复根因 |
| 语义暂停（`AgentPauseException`） | 业务工具主动抛出 | "需要外部介入再继续" | 是，带 partialContext 恢复 |
| 外部停止（`AgentStoppedException`） | 调用方 `stop()` | "用户/上游要求中止" | 是，带 partialContext 恢复 |

---

## 三、新增 Builder 方法

在 `McpAgentExecutor.builder()` 链上，新增三个运行时控制方法：

```java
McpAgentExecutor agent = McpAgentExecutor.builder(chainActor)
        .llm(...)
        .tools(...)
        .maxIterations(10)              // 原有：最大迭代轮数
        .maxDurationSeconds(30)         // 新增：最长执行时长（秒），0 = 不限
        .maxConsecutiveToolFailures(3)  // 新增：LLM 连续调用失败工具的最大轮数，0 = 不限
        .toolRetry(2)                   // 新增：框架层自动重试次数，0 = 不重试
        .build();
```

**语义细节**：

- `maxDurationSeconds`：从 `invoke()` 开始计时，超时不会强制中断正在执行的工具，而是等工具返回后在下一个 `shouldContinue()` 检查点触发 `TIMEOUT`。
- `maxConsecutiveToolFailures`：计数的是 **LLM 的行为**——LLM 连续多少轮都调用了最终失败的工具。工具失败计数在工具成功一次后清零。
- `toolRetry`：在 **框架层** 静默重试，LLM 不感知中间的失败。只有重试次数耗尽后仍失败，错误才会作为 `observation` 返回给 LLM，并计入连续失败计数。

---

## 四、Runtime 中止：AgentAbortException

### 4.1 MAX_STEPS：防止无限迭代

`maxIterations` 限制 LLM 推理的最大轮数。当任务复杂度超过设定上限时，框架在循环入口的 `shouldContinue()` 检查中抛出 `AgentAbortException(MAX_STEPS)`。

```java
Tool weatherTool = Tool.builder()
        .name("get_weather")
        .description("查询指定城市的天气")
        .params("city: String")
        .func(args -> {
            @SuppressWarnings("unchecked")
            String city = ((Map<String, Object>) args)
                    .getOrDefault("city", "unknown").toString();
            return city + ": 晴，26°C";
        })
        .build();

McpAgentExecutor agent = McpAgentExecutor.builder(chainActor)
        .llm(ChatAliyun.builder().model("qwen-plus").temperature(0f).build())
        .tools(weatherTool)
        .maxIterations(1)   // 只允许一轮 LLM 推理
        .verbose(true)
        .build();

try {
    // 需要查询 4 个城市，LLM 第一轮只能查一个，第二轮被截断
    agent.invoke("依次查询北京、上海、广州、深圳四个城市的天气，然后汇总");
    Assert.fail("应当抛出 AgentAbortException");
} catch (AgentAbortException e) {
    Assert.assertEquals(AgentAbortReason.MAX_STEPS, e.getReason());
    System.out.println("[MAX_STEPS] reason=" + e.getReason()
            + "  steps=" + e.getCompletedSteps().size());
    // 输出：[MAX_STEPS] reason=MAX_STEPS  steps=1
}
```

`getCompletedSteps()` 返回中止前已完成的步骤，便于记录日志或分析 LLM 行为模式。

---

### 4.2 TIMEOUT：防止执行时间失控

`maxDurationSeconds` 设置 Agent 总执行时长上限。典型场景：HTTP 网关有 30 秒超时，Agent 执行时间需要受控。

```java
Tool slowTool = Tool.builder()
        .name("slow_search")
        .description("执行一次耗时较长的搜索")
        .params("query: String")
        .func(args -> {
            try { Thread.sleep(4000); } catch (InterruptedException ignored) {}
            return "搜索完成";
        })
        .build();

McpAgentExecutor agent = McpAgentExecutor.builder(chainActor)
        .llm(ChatAliyun.builder().model("qwen-plus").temperature(0f).build())
        .tools(slowTool)
        .maxDurationSeconds(1)   // 允许最多 1 秒
        .verbose(true)
        .build();

try {
    agent.invoke("帮我搜索一些资料");
    Assert.fail("应当抛出 AgentAbortException");
} catch (AgentAbortException e) {
    Assert.assertEquals(AgentAbortReason.TIMEOUT, e.getReason());
    System.out.println("[TIMEOUT] reason=" + e.getReason());
    // 工具执行了 4 秒，返回后被检测到超时
}
```

注意：框架**不会强制中断**正在执行的工具线程，而是在工具返回后检测。这保证了工具的原子性（与 `AgentStoppedException` 的设计一致），代价是实际总时长会略超 `maxDurationSeconds`。

---

### 4.3 CONSECUTIVE_TOOL_FAILURES：防止无意义的错误循环

当下游服务持续不可用时，LLM 会在每一轮都尝试调用相同的工具，陷入无意义的错误循环。`maxConsecutiveToolFailures` 在连续失败达到阈值时主动中止。

```java
Tool failingTool = Tool.builder()
        .name("unstable_api")
        .description("调用一个不稳定的外部 API")
        .params("query: String")
        .func(args -> {
            throw new RuntimeException("连接超时，无法访问 API");
        })
        .build();

McpAgentExecutor agent = McpAgentExecutor.builder(chainActor)
        .llm(ChatAliyun.builder().model("qwen-plus").temperature(0f).build())
        .tools(failingTool)
        .maxConsecutiveToolFailures(1)   // LLM 连续失败 1 轮即中止
        .verbose(true)
        .build();

try {
    agent.invoke("调用 API 查询最新数据");
    Assert.fail("应当抛出 AgentAbortException");
} catch (AgentAbortException e) {
    Assert.assertEquals(AgentAbortReason.CONSECUTIVE_TOOL_FAILURES, e.getReason());
    System.out.println("[CONSECUTIVE_TOOL_FAILURES] reason=" + e.getReason()
            + "  steps=" + e.getCompletedSteps().size());
}
```

---

## 五、toolRetry：框架层透明重试

`toolRetry` 与 `maxConsecutiveToolFailures` 在语义上是互补的：

- `toolRetry`：**框架替 LLM 兜底**。在把错误 observation 回给 LLM 之前，先静默重试 N 次。如果重试成功，LLM 看到的是成功结果，完全不感知中间的失败。
- `maxConsecutiveToolFailures`：**观测 LLM 的行为**。只有当重试耗尽、错误真正交给 LLM、且 LLM 再次调用仍失败，才计入连续失败次数。

```java
AtomicInteger callCount = new AtomicInteger(0);

Tool flakyTool = Tool.builder()
        .name("flaky_api")
        .description("查询数据（偶发网络抖动）")
        .params("query: String")
        .func(args -> {
            int n = callCount.incrementAndGet();
            if (n <= 2) throw new RuntimeException("网络抖动 attempt=" + n);
            return "查询成功，数据返回正常";
        })
        .build();

McpAgentExecutor agent = McpAgentExecutor.builder(chainActor)
        .llm(ChatAliyun.builder().model("qwen-plus").temperature(0f).build())
        .tools(flakyTool)
        .toolRetry(2)   // 框架最多重试 2 次
        .verbose(true)
        .build();

var result = agent.invoke("通过 flaky_api 查询最新数据");

Assert.assertEquals("工具应被调用 3 次（2 次失败 + 1 次成功）", 3, callCount.get());
System.out.println("[toolRetry] callCount=" + callCount.get()
        + "  result=" + result.getText());
// 输出：[toolRetry] callCount=3  result=...（正常完成）
```

重试时序：

```
框架调用 flaky_api(attempt=1) → RuntimeException  ← 框架静默重试
框架调用 flaky_api(attempt=2) → RuntimeException  ← 框架静默重试
框架调用 flaky_api(attempt=3) → "查询成功"        ← 成功
LLM 收到 observation: "查询成功，数据返回正常"     ← LLM 从未看到失败
```

对比没有 `toolRetry` 的情况：前两次失败会作为 `observation` 告知 LLM，LLM 可能再次调用（计入连续失败），也可能直接返回错误信息给用户。

---

## 六、语义暂停：AgentPauseException

### 6.1 设计意图

`AgentAbortException` 是框架对"不应继续"的判断，是系统级的。`AgentPauseException` 则完全不同——它是**业务逻辑主动发起的暂停请求**，含义是"我需要外部世界的介入，然后再继续"。

典型场景：
- 转账金额超过自动审批限额，需要主管审批
- 生成的合同草稿需要法务确认后才能发送
- 敏感数据访问需要二次身份验证

### 6.2 工具内抛出 AgentPauseException

工具通过 `ContextBus` 取得当前执行上下文，然后携带业务 payload 抛出暂停：

```java
Tool transferTool = Tool.builder()
        .name("transfer_money")
        .description("执行转账操作")
        .params("amount: String, to: String")
        .func(args -> {
            @SuppressWarnings("unchecked")
            Map<String, Object> map = (Map<String, Object>) args;

            // 从 ContextBus 获取当前 Agent 执行上下文
            AgentTaskContext ctx = ContextBus.get()
                    .getTransmit(CallInfo.AGENT_TASK_CTX.name());

            // 金额超限，主动暂停，等待人工审批
            throw new AgentPauseException(
                    "need_approval",                          // reason：由业务定义
                    Map.of(
                        "amount", map.getOrDefault("amount", ""),
                        "to",     map.getOrDefault("to", ""),
                        "reason", "单笔金额超过自动审批限额"
                    ),
                    ctx                                       // 保存当前上下文快照
            );
        })
        .build();
```

### 6.3 捕获暂停并恢复执行

```java
McpAgentExecutor agent = McpAgentExecutor.builder(chainActor)
        .llm(ChatAliyun.builder().model("qwen-plus").temperature(0f).build())
        .tools(transferTool)
        .verbose(true)
        .build();

// ── 第一次 invoke：触发暂停 ─────────────────────────────────────
AgentPauseException paused = null;
try {
    agent.invoke("帮我向张三转账 50000 元");
    Assert.fail("应当抛出 AgentPauseException");
} catch (AgentPauseException e) {
    paused = e;
    System.out.println("[暂停] reason=" + e.getReason()
            + "  payload=" + e.getPayload());
    // 输出：[暂停] reason=need_approval
    //         payload={amount=50000, to=张三, reason=单笔金额超过自动审批限额}
}

// ── 审批流程（业务逻辑，此处省略）─────────────────────────────
// approvalService.submit(paused.getPayload());
// approvalResult = approvalService.waitForResult();

// ── 第二次 invoke：审批通过，带 partialContext 恢复 ──────────────
Tool approvedTransferTool = Tool.builder()
        .name("transfer_money")
        .description("执行转账操作（已审批）")
        .params("amount: String, to: String")
        .func(args -> {
            @SuppressWarnings("unchecked")
            Map<String, Object> map = (Map<String, Object>) args;
            return "转账成功：向 " + map.getOrDefault("to", "")
                    + " 转账 " + map.getOrDefault("amount", "") + " 元";
        })
        .build();

McpAgentExecutor approvedAgent = McpAgentExecutor.builder(chainActor)
        .llm(ChatAliyun.builder().model("qwen-plus").temperature(0f).build())
        .tools(approvedTransferTool)
        .verbose(true)
        .build();

// 用暂停时保存的 partialContext 恢复执行
AgentTaskContext savedCtx = paused.getPartialContext();
var result = approvedAgent.invoke("帮我向张三转账 50000 元", savedCtx);

System.out.println("[恢复结果] " + result.getText());
// 输出：[恢复结果] 转账成功：向张三转账 50000 元
```

执行流程：

```
第一次 invoke：
  LLM 推理 → 调用 transfer_money(amount=50000, to=张三)
  工具内检测到超限 → throw AgentPauseException("need_approval", payload, ctx)
  框架捕获 → 保存 partialContext → 向上抛出

[外部：提交审批，等待结果]

第二次 invoke（带 partialContext）：
  [跳过已完成的步骤，直接继续]
  LLM 推理 → 调用 transfer_money（此时为已审批版本）
  工具正常返回 → LLM 生成最终回复
```

---

## 七、各停止类型完整对比

| 停止类型 | 触发方式 | 异常类 | reason | 携带数据 | 可恢复 | 典型场景 |
|----------|----------|--------|--------|----------|--------|----------|
| 外部停止 | `agent.stop()` | `AgentStoppedException` | `user_cancel` | `partialContext` | 是 | 用户取消、上游超时 |
| 最大迭代 | `maxIterations` 超限 | `AgentAbortException` | `MAX_STEPS` | `completedSteps` | 不建议 | 防死循环 |
| 执行超时 | `maxDurationSeconds` 超限 | `AgentAbortException` | `TIMEOUT` | `completedSteps` | 不建议 | 网关超时保护 |
| 连续失败 | `maxConsecutiveToolFailures` 超限 | `AgentAbortException` | `CONSECUTIVE_TOOL_FAILURES` | `completedSteps` | 不建议 | 下游服务故障熔断 |
| 语义暂停 | 工具内 `throw new AgentPauseException(...)` | `AgentPauseException` | 业务自定义 | `payload` + `partialContext` | 是 | 人工审批、二次确认 |

`AgentAbortException` 说"继续执行没有意义"，是系统对 LLM 行为的强制干预；`AgentPauseException` 说"我需要外部配合才能继续"，是业务逻辑对流程的主动控制。两者的 `partialContext` 语义相同：已完成步骤的快照，可用于后续恢复。

---

## 八、框架内部执行流

```
invoke(question)
  │
  ▼
[初始化执行上下文，记录 startTime]
  │
  ┌─ 执行循环 ───────────────────────────────────────────────────┐
  │   ▼                                                          │
  │  shouldContinue()?                                           │
  │   ├── stopSignal=true      → throw AgentStoppedException     │
  │   ├── i >= maxIterations   → throw AgentAbortException(MAX_STEPS)
  │   ├── elapsed > maxDuration → throw AgentAbortException(TIMEOUT)
  │   └── consecutiveFails >= limit → throw AgentAbortException(CONSECUTIVE_TOOL_FAILURES)
  │                                                              │
  │   ▼                                                          │
  │  LLM 推理                                                    │
  │   │                                                          │
  │   ├── Final Answer → 返回结果，退出循环                      │
  │   └── Tool Call ──────────────────────────────────────────── │
  │         ▼                                                    │
  │        框架调用工具（最多重试 toolRetry 次）                 │
  │         ├── 成功 → observation 回给 LLM，consecutiveFails=0  │
  │         ├── toolRetry 耗尽仍失败 → observation=错误信息，    │
  │         │                           consecutiveFails++       │
  │         └── AgentPauseException → 保存 partialContext，向上抛│
  └──────────────────────────────────────────────────────────────┘
```

---

## 九、运行前置条件

1. **`ALIYUN_KEY`** 环境变量：示例使用 `qwen-plus`，需配置通义千问 API Key
2. 无需额外 classpath 资源文件，所有工具在代码中构造
3. `testTimeout` 依赖真实的工具执行时长，建议在非资源受限环境下运行
4. `testPauseAndResume` 第二次 `invoke` 构造了新的 `McpAgentExecutor` 实例（替换工具），与实际生产中注入审批结果后重建 Agent 的做法一致

---

## 十、总结

j-langchain 的 Agent 停止体系围绕两条线展开：

**Runtime 中止（`AgentAbortException`）**：框架的自我保护机制。当 LLM 的行为已经偏离正轨——超时、死循环、持续调用失败工具——框架主动干预，用枚举化的 `reason` 告知调用方"是什么原因导致终止"，便于监控告警和问题定位。设计原则是**观测 LLM 行为，在有意义的检查点中止**，而不是强制中断线程。

**语义暂停（`AgentPauseException`）**：业务流程的主动挂起。工具作为流程的执行单元，在遇到需要外部介入的情形时，携带结构化的 `payload` 和 `partialContext` 向外"请求暂停"。框架保存好执行现场，审批/确认完成后用 `partialContext` 恢复，Agent 从中断点继续，已完成的步骤不重复执行。设计原则是**工具表达意图，框架保存现场，外部决策后恢复**。

两者共同构成了从"用户取消"到"系统熔断"再到"业务审批"的完整停止语义，让 Agent 从难以控制的黑盒变成可预期、可观测、可恢复的业务流程组件。

---

> 相关资源
> - 完整代码：[Article26AgentStopTypes.java](../../../src/test/java/org/salt/jlangchain/demo/article/Article26AgentStopTypes.java)
> - 前置阅读：[Agent 停止与恢复：可中断的长任务](24-stop-and-resume.md)
> - j-langchain GitHub：https://github.com/flower-trees/j-langchain
> - 运行环境：`ALIYUN_KEY`（`qwen-plus`）
