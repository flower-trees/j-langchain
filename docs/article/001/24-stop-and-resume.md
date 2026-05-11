# Agent 停止与恢复：可中断的长任务

> **标签**：`Java` `stop` `resume` `AgentStoppedException` `partialContext` `可中断` `j-langchain`  
> **前置阅读**：[SubAgent 进阶：LLM 策略、工具借用与 Skill 嵌套](23b-subagent-advanced)  
> **适合人群**：需要构建可中断、可恢复的 Agent 任务系统的 Java 开发者

---

## 一、问题：长任务 Agent 的不可控性

一个查询多个城市信息的 Agent 可能需要调用十几次工具、消耗数十秒。在这段时间里：

- 用户改变了主意，想取消任务
- 上游超时触发，需要立即中止
- 任务完成了一半，用户想调整目标继续执行

传统做法是让线程自然跑完，或粗暴地 `interrupt()`——前者浪费资源，后者丢失已完成的进度。

j-langchain 提供了一套**受控停止与恢复机制**：
- `agent.stop()` 发出停止信号，Agent 在下一个安全检查点中止
- `AgentStoppedException` 携带 `partialContext`，保存已完成的工具调用步骤
- `agent.invoke(question, partialContext)` 从中断点恢复，跳过已完成的步骤
- `context.createWithSteps()` 把旧步骤注入新指令，实现"带着进度换方向"

---

## 二、停止机制概览

```
主线程                          Agent 执行线程
  │                                  │
  │  CompletableFuture.supplyAsync() │
  │─────────────────────────────────>│
  │                                  │ 调用工具（慢速）
  │  toolStarted.await()             │   工具执行中...
  │<─ latch.countDown()              │
  │                                  │
  │  agent.stop()                    │
  │──── 设置 stopSignal = true ─────>│
  │                                  │ 工具返回后检查 shouldContinue()
  │                                  │   stopSignal=true → 中止
  │                                  │   抛出 AgentStoppedException
  │<─────────────────────────────────│
  │  future.get() 抛 ExecutionException
  │  getCause() → AgentStoppedException
  │  getPartialContext() → 已完成步骤
```

Agent 不会在工具执行中途强制中止，而是等当前工具返回后，在进入下一轮 LLM 推理之前检查停止信号。这保证了工具调用的原子性——要么完整执行，要么根本不执行。

---

## 三、基础 stop：触发 AgentStoppedException

```java
CountDownLatch toolStarted = new CountDownLatch(1);

McpAgentExecutor agent = McpAgentExecutor.builder(chainActor)
        .llm(ChatAliyun.builder().model("qwen-plus").temperature(0f).build())
        .tools(slowTool("search_city", "查询城市旅游信息", toolStarted, 500))
        .verbose(true)
        .build();

// 异步执行 Agent
CompletableFuture<ChatGeneration> future = CompletableFuture.supplyAsync(
        () -> agent.invoke("依次查询成都、西安、桂林的旅游信息并给出推荐"));

// 等工具开始执行后再 stop
toolStarted.await(10, TimeUnit.SECONDS);
agent.stop();

try {
    future.get(15, TimeUnit.SECONDS);
    Assert.fail("应当抛出 AgentStoppedException");
} catch (ExecutionException e) {
    Assert.assertTrue(e.getCause() instanceof AgentStoppedException);
    System.out.println("[stop 成功] cause: " + e.getCause().getMessage());
}
```

`slowTool` 工具在被调用时触发 `latch.countDown()`，然后 `sleep(500ms)` 再返回。测试线程在 latch 触发后立即调用 `agent.stop()`，确保停止信号在工具返回后的 `shouldContinue()` 检查前被设置。

---

## 四、partialContext：保存已完成的步骤

`AgentStoppedException` 不是一个普通的异常——它携带了 Agent 在中止前已经完成的所有工具调用步骤：

```java
} catch (ExecutionException e) {
    Assert.assertTrue(e.getCause() instanceof AgentStoppedException);
    AgentStoppedException stopped = (AgentStoppedException) e.getCause();

    // partialContext 包含完整的执行上下文
    Assert.assertNotNull("partialContext 不应为 null", stopped.getPartialContext());

    // completedSteps 是已完成的工具调用步骤列表
    List<AgentStep> steps = stopped.getCompletedSteps();
    Assert.assertNotNull("completedSteps 不应为 null", steps);

    System.out.println("[已完成步骤数] " + steps.size());
    // 输出示例：[已完成步骤数] 1
    // 第一个 step：search_city("成都") 的调用结果已保存
}
```

`AgentStep` 的结构：

```
AgentStep
  ├── action (工具调用请求：工具名 + 参数)
  └── observation (工具返回值)
```

每个完整的"工具调用 + 结果"对构成一个 `AgentStep`。停止时已完成的 step 被保存在 `partialContext` 里，可以在下次 `invoke()` 时重用，避免重复执行。

---

## 五、stop 信号透传到 SubAgent

当主 Agent 使用了 SubAgent，`master.stop()` 会通过 `ContextBus` 将停止信号级联传入 SubAgent 的内部执行循环：

```java
SubAgentConfig config = new SubAgentConfig();
config.setName("travel_researcher");
config.setDescription("旅行信息研究员，查询指定城市的旅行信息");

CountDownLatch subToolStarted = new CountDownLatch(1);
SubAgent researcher = SubAgent.from(config, chainActor)
        .llm(ChatAliyun.builder().model("qwen-plus").temperature(0f).build())
        .tools(slowTool("search_city", "查询城市旅游信息", subToolStarted, 500))
        .build();

McpAgentExecutor master = McpAgentExecutor.builder(chainActor)
        .llm(ChatAliyun.builder().model("qwen-plus").temperature(0f).build())
        .subAgent(researcher)
        .systemPrompt("你是旅行总助手，需要查询旅行信息时请调用 travel_researcher。")
        .verbose(true)
        .build();

CompletableFuture<ChatGeneration> future = CompletableFuture.supplyAsync(
        () -> master.invoke("查询成都的旅行信息"));

subToolStarted.await(10, TimeUnit.SECONDS);
master.stop(); // 停止 master，信号通过 ContextBus 传递到 SubAgent

try {
    future.get(15, TimeUnit.SECONDS);
    Assert.fail("应当抛出 AgentStoppedException");
} catch (ExecutionException e) {
    Assert.assertTrue("stop 信号应从 SubAgent 传播到 Master",
            e.getCause() instanceof AgentStoppedException);
    System.out.println("[SubAgent 停止传播成功]");
}
```

停止信号传播链：

```
master.stop()
  │
  └── ContextBus.transmit["STOP_SIGNAL"] = true
                │
                ▼
        SubAgent.invoke() 检查 ContextBus
                │
                └── executor.invoke(input, parentSignal)
                        SubAgent 内部执行器检查 shouldContinue()
                        → false → 抛出 AgentStoppedException
```

不管嵌套多少层（Master → SubAgent → Skill → 内嵌 SubAgent），停止信号通过 `ContextBus` 逐层传递，整个调用链会同步停止，不会出现部分停止、部分继续的情况。

---

## 六、用 partialContext 恢复执行

停止后，可以把 `AgentStoppedException` 携带的 `partialContext` 传给下次 `invoke()`，Agent 会跳过已完成的步骤，从中断点继续执行：

```java
McpAgentExecutor agent = McpAgentExecutor.builder(chainActor)
        .llm(ChatAliyun.builder().model("qwen-plus").temperature(0f).build())
        .tools(
            slowTool("search_city", "查询城市旅游信息", toolStarted, 400),
            fastTool("get_hotel",   "查询酒店价格")
        )
        .verbose(true)
        .build();

String question = "查询成都的旅游信息和酒店价格，然后给出建议";

// ── 第一次：执行中被 stop ─────────────────────────────────────
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

System.out.println("[第一次 stop，已完成步骤] " + stopped.getCompletedSteps().size());

// ── 第二次：带 partialContext 恢复，不再 stop ─────────────────
AgentTaskContext partialCtx = stopped.getPartialContext();
ChatGeneration result = agent.invoke(question, partialCtx);  // 传入上次的上下文

Assert.assertFalse("恢复后结果文本不应为空", result.getText().isBlank());
System.out.println("[恢复结果]\n" + result.getText());
```

执行对比：

```
第一次执行：
  Step 1: search_city("成都") → "成都旅游信息..."   ← 完成
  [stop 信号] 中止，抛出 AgentStoppedException
  partialContext 保存了 step 1

第二次执行（带 partialContext）：
  [跳过 step 1，直接使用缓存结果]
  Step 2: get_hotel("成都") → "成都：三星¥280/晚..." ← 继续执行
  Final Answer: 成都旅游建议...
```

已完成的步骤不会重新执行，节省了 LLM 调用次数和工具调用开销。

---

## 七、createWithSteps：把旧步骤注入新指令

有时停止后不想重复原来的问题，而是想换一个方向——但又不想丢弃之前已经查到的数据。`createWithSteps()` 允许外层手动把旧步骤注入到新指令的上下文里：

```java
FullContext context = FullContext.build();

McpAgentExecutor agent = McpAgentExecutor.builder(chainActor)
        .llm(ChatAliyun.builder().model("qwen-plus").temperature(0f).build())
        .context(context)   // 注入同一个 context 对象，后续 createWithSteps 用到
        .tools(
            slowTool("search_city", "查询城市旅游信息", toolStarted, 400),
            fastTool("get_hotel",   "查询酒店价格")
        )
        .verbose(true)
        .build();

// ── 先 stop，拿到已完成步骤 ─────────────────────────────────
CompletableFuture<ChatGeneration> firstRun = CompletableFuture.supplyAsync(
        () -> agent.invoke("查询成都的旅游和酒店信息"));

toolStarted.await(10, TimeUnit.SECONDS);
agent.stop();

AgentStoppedException stopped = ...;
List<AgentStep> priorSteps = stopped.getCompletedSteps();
System.out.println("[已有步骤] " + priorSteps.size());

// ── 把旧步骤注入新指令 ──────────────────────────────────────
String newQuestion = "基于已有信息，改为推荐西安之旅，并查询西安酒店";
AgentTaskContext newCtx = context.createWithSteps(newQuestion, null, priorSteps);

ChatGeneration result = agent.invoke(newQuestion, newCtx);
System.out.println("[新指令结果]\n" + result.getText());
```

`createWithSteps()` 的语义：

```
priorSteps（来自旧任务的已完成步骤）
  + newQuestion（新的任务指令）
  ↓
AgentTaskContext（新的执行上下文）
  Agent 在这个上下文里继续执行：
  - 旧步骤作为"已知信息"传给 LLM
  - LLM 基于旧信息回答新问题，避免重复查询
```

**适用场景**：用户在任务进行中途调整了目标，但已查询的数据（如天气、机票信息）对新目标仍然有参考价值。

---

## 八、三种恢复策略对比

| 策略 | API | 适用场景 |
|------|-----|---------|
| 重新开始 | `agent.invoke(question)` | 已完成步骤无价值，全部重做 |
| 断点续传 | `agent.invoke(question, partialCtx)` | 同一问题继续，跳过已完成步骤 |
| 带旧步骤换指令 | `context.createWithSteps(newQ, null, priorSteps)` | 换了问题，但旧步骤数据仍有价值 |

---

## 九、运行前置条件

1. **`ALIYUN_KEY`** 环境变量：示例使用 `qwen-plus`
2. 无需 classpath 资源文件，所有工具在代码中构造
3. 停止测试依赖多线程时序，建议在非资源受限环境下运行

---

## 十、总结

Agent 停止与恢复机制解决了长任务 Agent 的可控性问题：

- **受控停止**：`agent.stop()` 不强制中断，等当前工具返回后在安全检查点中止，保证工具调用的原子性
- **进度保存**：`AgentStoppedException.getPartialContext()` 携带已完成的所有步骤，中断即保存
- **信号级联**：停止信号通过 `ContextBus` 从主 Agent 传入 SubAgent，整个调用链同步停止
- **三种恢复策略**：重新开始、断点续传、带旧步骤换指令，覆盖不同业务场景

这套机制在实际生产中能支撑很多关键场景：用户取消操作、超时降级、人工审核后继续、动态调整任务目标等——长任务 Agent 从"不可控的黑盒"变成了"可暂停、可恢复、可重定向的可控过程"。

---

> 📎 相关资源
> - 完整代码：[Article24StopAndResume.java](../../../src/test/java/org/salt/jlangchain/demo/article/Article24StopAndResume.java)
> - j-langchain GitHub：https://github.com/flower-trees/j-langchain
> - 运行环境：`ALIYUN_KEY`（`qwen-plus`）
