# Agent 停止与继续设计

## 1. 设计背景与目标

Agent 在执行长时任务时（多工具调用、嵌套 Skill/SubAgent），存在以下两类控制需求：

**停止（Stop）**：外部线程（如 HTTP 取消、用户中断、超时守卫）需要在任意时刻终止正在运行的 Agent 循环，包括深层嵌套的 Skill/SubAgent，同时保留已执行完成的步骤现场。

**继续（Resume/Continue）**：在停止或失败后，基于已有执行上下文恢复执行同一任务，或将已完成的步骤注入新任务指令，避免重复调用。

j-langchain 的设计目标是：**在不修改 salt-function-flow 流程引擎核心语义的前提下，通过共享信号量 + ContextBus 传递实现跨层停止，并通过可预加载的 AgentTaskContext 实现无感知恢复**，Agent 本身不需要区分是首次执行还是恢复执行。

---

## 2. 为何不用 FlowInstance.stop()

salt-function-flow 的 `FlowInstance.stop()` 调用的是 `ContextBus.get().stopProcess()`，而 `ContextBus` 基于 `ThreadLocal`，每次 `FlowInstance.execute()` 创建的都是当前线程独有的实例。

```
主线程：FlowInstance.execute() → ContextBus（ThreadLocal A）
控制线程：FlowInstance.stop() → ContextBus.get() → ThreadLocal 上的 B（不同实例）
                                ↑ 无效，信号永远不会到达执行线程
```

跨线程控制必须使用**堆上的共享可变状态**，而不是 ThreadLocal。

---

## 3. 核心机制：AtomicBoolean 信号量

### 3.1 信号量的生命周期

每次 `invoke()` 调用时：
- 若调用方未传入外部信号量，则创建一个新的 `AtomicBoolean(false)` 并持有其引用
- 若调用方传入了外部信号量（SubAgent/Skill 的传播路径），则直接使用该引用
- 信号量引用通过 `ContextBus.transmitMap` 注入，key 为 `CallInfo.STOP_SIGNAL`

`stop()` 方法只做一件事：将信号量设为 `true`。

```java
public void stop() {
    stopSignal.set(true);   // 线程安全，立即对所有使用该引用的线程可见
}
```

### 3.2 检查点：shouldContinue

每次循环迭代开始时，`shouldContinue` lambda 优先检查停止信号：

```java
Function<Integer, Boolean> shouldContinue = i -> {
    AtomicBoolean signal = ContextBus.get().getTransmit(CallInfo.STOP_SIGNAL.name());
    if (signal != null && signal.get()) {
        AgentTaskContext ctx = ContextBus.get().getTransmit(CallInfo.AGENT_TASK_CTX.name());
        throw new AgentStoppedException("Agent stopped by external request", ctx);
    }
    // ...正常循环条件判断
};
```

检查点位于循环条件而非工具调用内部，这意味着：
- 工具正在执行时 stop() 被调用，当前工具会**执行完成**，stop 在工具返回后的下一轮 shouldContinue 中生效
- 不会破坏工具的原子性，也不会产生半完成的步骤

### 3.3 AgentStoppedException

停止信号触发时抛出 `AgentStoppedException`，它携带停止时刻的完整上下文：

```java
public class AgentStoppedException extends RuntimeException
        implements FlowControlException {   // 见第 6 节

    private final AgentTaskContext partialContext;

    public AgentTaskContext getPartialContext() { return partialContext; }

    // 便捷方法：获取已完成步骤列表
    public List<AgentStep> getCompletedSteps() {
        return partialContext != null ? partialContext.getCompletedSteps() : List.of();
    }
}
```

`invoke()` 的顶层捕获块：

```java
try {
    return (ChatGeneration) chainActor.invoke(agentChain, ...);
} catch (AgentStoppedException e) {
    if (conversationStorer != null) conversationStorer.savePartial(e.getPartialContext());
    throw e;   // 继续向调用方传播
}
```

---

## 4. 停止信号在 Skill / SubAgent 中的传播

Skill 和 SubAgent 各自运行独立的 `McpAgentExecutor` 实例，拥有自己的 `FlowInstance` 和 ContextBus。信号量必须显式传递。

传播路径：主 Agent ContextBus → 工具调用时读取 → 传入子 Executor 的 `externalSignal` 参数。

```java
// SubAgent.invoke() / Skill.invoke()
AtomicBoolean parentSignal = null;
if (ContextBus.get() != null) {
    parentSignal = ((IContextBus) ContextBus.get())
            .getTransmit(CallInfo.STOP_SIGNAL.name());
}
return executor.invoke(input, parentSignal).getText();
```

子 Executor 在自己的 `invoke()` 中将这个信号量注入自己的 ContextBus transmit map，使子循环的 `shouldContinue` 也能感知同一个停止信号。

```
master.stop() → stopSignal.set(true)
                   │
                   ▼
master shouldContinue 或 executeTool
                   │
                   ▼
SubAgent.invoke(input, parentSignal)   ← 同一个 AtomicBoolean 引用
                   │
                   ▼
sub shouldContinue → signal.get() == true → AgentStoppedException
                   │
                   ▼
executeTool (master) 捕获到 AgentStoppedException
   ↓ catch(AgentStoppedException e) { throw e; }   ← 不作为工具错误处理，直接传播
                   │
                   ▼
master invoke() 捕获 → savePartial() + 向调用方传播
```

关键设计：在 `executeTool` 的异常捕获中，`AgentStoppedException` 必须优先于通用 `Exception` 处理，否则会被误记录为工具执行错误：

```java
try {
    Object raw = tool.getFunc().apply(argsMap);
    ...
} catch (AgentStoppedException e) {
    log.debug("Tool '{}' interrupted by stop signal", toolName);
    throw e;                          // 直接透传，不设置 observation
} catch (Exception e) {
    observation = "Tool execution error: " + e.getMessage();
    log.error("Tool execution failed: {}", toolName, e);
}
```

---

## 5. 继续执行（Resume）

### 5.1 预加载上下文

恢复执行的核心是让新的 `invoke()` 调用使用停止时保存的 `AgentTaskContext`，而不是创建空白上下文。

调用方从 `AgentStoppedException` 中取出 `partialContext` 传入新的 `invoke()`：

```java
AgentTaskContext partialCtx = stoppedException.getPartialContext();
ChatGeneration result = agent.invoke(question, partialCtx);
```

`initContext` 节点优先使用预加载上下文：

```java
AgentTaskContext ctx = ContextBus.get().getTransmit(CallInfo.PRELOADED_CTX.name());
if (ctx == null) {
    ctx = contextFinal.create(question, systemPromptFinal);   // 正常首次执行
}
ContextBus.get().putTransmit(CallInfo.AGENT_TASK_CTX.name(), ctx);
```

### 5.2 恢复时 LLM 首次调用必须看到已有步骤

存在一个重要细节：`prompt` 节点（`ChatPromptTemplate`）输出的是仅含 `[system?, human]` 的 `ChatPromptValue`，不包含 `AgentTaskContext` 中的历史步骤。若直接将其送入 LLM，第一次调用不知道工具已被调用过，会重复执行。

解决方案：在 `prompt` 节点与 `loop` 之间插入 `applyPreloadedSteps` 节点：

```java
TranslateHandler<Object, Object> applyPreloadedSteps = new TranslateHandler<>(promptValue -> {
    AgentTaskContext ctx = ContextBus.get().getTransmit(CallInfo.AGENT_TASK_CTX.name());
    if (ctx != null && !ctx.getCompletedSteps().isEmpty()) {
        return ctx.buildChatPromptValue();   // 替换为含历史步骤的完整上下文
    }
    return promptValue;   // 首次执行，保持原样
});

// 链结构：
.next(initContext)
.next(prompt)
.next(applyPreloadedSteps)   // 恢复时补全历史步骤
.loop(shouldContinue, ...)
```

恢复时 LLM 首轮输入：

```
user: 查询成都的旅游信息和酒店价格，然后给出建议
assistant(tool_calls): search_city {"city":"成都"}
tool[search_city]: 成都: 旅游信息...
```

LLM 看到已有的工具结果，可以继续完成剩余部分，而非重新开始。

### 5.3 用已有步骤继续新指令（createWithSteps）

`AgentContext` 接口提供了 `createWithSteps()` 默认方法，允许外层将旧步骤注入新指令的上下文：

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

使用场景：任务被打断后，外层决定用同样的步骤切换到新指令：

```java
List<AgentStep> priorSteps = stoppedException.getCompletedSteps();
AgentTaskContext newCtx = context.createWithSteps(newQuestion, null, priorSteps);
ChatGeneration result = agent.invoke(newQuestion, newCtx);
```

---

## 6. 与 salt-function-flow 的集成：FlowControlException

`FlowNodeLoop.doProcessGateway` 对内部执行中的所有异常统一通过 `ContextBus.putException()` 记录，默认行为是打印 WARN 级别日志加完整 stack trace：

```java
// FlowNodeLoop.java
try {
    execute(info);
} catch (Exception e) {
    ((ContextBus) iContextBus).putException(info.getIdOrAlias(), e);
    throw e;
}
```

`AgentStoppedException` 是预期的控制流信号而非故障，打印 ERROR/WARN 会造成误导。

解决方案：在 salt-function-flow 中引入 `FlowControlException` 标记接口，`ContextBus.putException()` 对实现该接口的异常降级为 DEBUG：

```java
// FlowControlException.java（salt-function-flow）
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

// AgentStoppedException.java（j-langchain）
public class AgentStoppedException extends RuntimeException
        implements FlowControlException { ... }
```

---

## 7. ToolMessage 消息重建问题

实现过程中发现一个影响 resume 正确性的深层问题：**LLM 的 tool_call 响应在重建消息时 role 错误**。

### 7.1 问题根源

`ToolMessage`（LLM 返回的含 tool_calls 的 AIMessage）默认 role 为 `"tool"`。`BaseChatModel.convertMessage()` 按 role 进行 switch 分发：

```
TOOL → AiChatInput.Message(role="tool", content, name, toolCallId)
```

这将 LLM 的 tool_call 响应错误地构造为"工具结果消息"，`toolCalls` 字段被完全丢弃。API 实际收到：

```
user: 问题
tool: (空内容，无 tool_call_id)        ← 错误：应为 assistant + tool_calls
tool[search_city]: 成都: 查询完成
tool[get_hotel]: 成都: 查询完成
```

LLM 收到此无效序列后，不认为自己发出过 tool_calls，于是重新调用工具，形成死循环。

### 7.2 修复

在 `convertMessage()` 的 TOOL 分支中区分两类消息：
- `ToolMessage.toolCalls` 非空 → LLM 的响应，转为 `assistant` role + `tool_calls` 数组
- `ToolMessage.toolCallId` 非空 → 工具结果，保持 `tool` role

```java
case TOOL:
    if (baseMessage instanceof ToolMessage tm && !CollectionUtils.isEmpty(tm.getToolCalls())) {
        AiChatInput.Message assistantMsg =
            new AiChatInput.Message(RoleType.ASSISTANT.getCode(), tm.getContent() != null ? tm.getContent() : "");
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

正确的 API 消息序列：

```
user: 问题
assistant: (tool_calls: [{search_city, {city:成都}}, {get_hotel, {city:成都}}])
tool[search_city]: 成都: 查询完成
tool[get_hotel]: 成都: 查询完成
```

---

## 8. 整体数据流

```
────────── 正常执行 ──────────────────────────────────────────────────

agent.invoke(question)
  │
  ├─ 创建 stopSignal = new AtomicBoolean(false)
  ├─ transmitMap: STOP_SIGNAL → stopSignal
  │
  ▼
initContext 节点
  ├─ PRELOADED_CTX 为空 → 创建新 AgentTaskContext
  └─ 存入 AGENT_TASK_CTX

applyPreloadedSteps 节点
  └─ completedSteps 为空 → 直接透传 prompt 输出

loop (shouldContinue → LLM → executeTool)
  ├─ shouldContinue: signal.get() == false → 继续
  ├─ LLM 调用
  ├─ executeTool: 执行工具 → ctx.addStep() → buildChatPromptValue()
  └─ 循环直到 Final Answer

────────── stop() 被调用 ─────────────────────────────────────────────

master.stop() ──→ stopSignal.set(true)

[某轮工具完成后]
shouldContinue: signal.get() == true
  └─ throw AgentStoppedException(ctx)
       │
       ▼
invoke() 捕获
  ├─ conversationStorer.savePartial(ctx)   [可选]
  └─ throw → 调用方 ExecutionException.getCause()

────────── resume: 继续执行 ──────────────────────────────────────────

partialCtx = stoppedException.getPartialContext()
agent.invoke(question, partialCtx)
  │
  ├─ 创建新 stopSignal（可再次停止）
  ├─ transmitMap: PRELOADED_CTX → partialCtx
  │
  ▼
initContext 节点
  └─ PRELOADED_CTX 非空 → 直接使用 partialCtx

applyPreloadedSteps 节点
  └─ completedSteps 非空 → 替换为 ctx.buildChatPromptValue()
       [LLM 首轮即看到 user + assistant(tool_calls) + tool[...]]

loop 从已有步骤继续 → Final Answer
```

---

## 9. invoke() 重载设计

`McpAgentExecutor` 和 `AgentExecutor` 提供四个 invoke 重载，全部委托给一个核心方法：

```java
// 统一入口
public ChatGeneration invoke(String input, AtomicBoolean externalSignal,
                              AgentTaskContext preloadedCtx)

// 常规调用
public ChatGeneration invoke(Object input)               // → invoke(str, null, null)
public ChatGeneration invoke(String input)               // → invoke(input, null, null)

// Skill/SubAgent 传播停止信号
public ChatGeneration invoke(String input,
                              AtomicBoolean externalSignal) // → invoke(input, signal, null)

// 恢复执行（外层提供历史上下文，内层可再次停止）
public ChatGeneration invoke(String input,
                              AgentTaskContext preloadedCtx) // → invoke(input, null, ctx)
```

`externalSignal` 和 `preloadedCtx` 可同时传入（如二次停止恢复场景），设计为独立正交参数。

---

## 10. 涉及改动的文件

### j-langchain

```
core/
  agent/
    AgentStoppedException.java    # 新增：携带 partialContext；实现 FlowControlException
    McpAgentExecutor.java         # 新增：stop()、invoke 重载、shouldContinue 检查、
                                  #       applyPreloadedSteps 节点、executeTool AgentStoppedException
                                  #       优先捕获、formatChatPromptValue 增强
    AgentExecutor.java            # 同 McpAgentExecutor，适用于 ReAct 模式
    memory/
      AgentTaskContext.java       # 新增 default getCompletedSteps()
      AgentContext.java           # 新增 default createWithSteps()
      FullContext.java            # 实现 getCompletedSteps()
      SlidingWindowContext.java   # 实现 getCompletedSteps()
  history/memory/
    ConversationMemoryStorerBase  # 新增 savePartial() 空实现（子类可覆盖）
  subagent/
    SubAgent.java                 # invoke() 透传父 stopSignal
  skill/
    Skill.java                    # invoke() 透传父 stopSignal
  common/
    CallInfo.java                 # 新增 STOP_SIGNAL、PRELOADED_CTX 枚举值
  llm/
    BaseChatModel.java            # convertMessage() TOOL 分支：区分 LLM 响应与工具结果
```

### salt-function-flow

```
FlowControlException.java         # 新增：标记接口，区分控制流异常与真实错误
context/
  ContextBus.java                 # putException() 对 FlowControlException 降级为 DEBUG
```

---

## 11. 使用示例

### 11.1 基础停止

```java
McpAgentExecutor agent = McpAgentExecutor.builder(chainActor)
    .llm(llm)
    .tools(searchTool)
    .build();

CompletableFuture<ChatGeneration> future = CompletableFuture.supplyAsync(
    () -> agent.invoke("查询成都、西安、桂林的旅游信息"));

// 任意线程调用 stop
Thread.sleep(500);
agent.stop();

try {
    future.get();
} catch (ExecutionException e) {
    AgentStoppedException stopped = (AgentStoppedException) e.getCause();
    System.out.println("已完成步骤: " + stopped.getCompletedSteps().size());
}
```

### 11.2 恢复执行

```java
AgentStoppedException stopped = ...;
AgentTaskContext partialCtx = stopped.getPartialContext();

// 用相同问题从中断点继续（可再次 stop）
ChatGeneration result = agent.invoke(question, partialCtx);
```

### 11.3 注入旧步骤继续新指令

```java
List<AgentStep> priorSteps = stopped.getCompletedSteps();
FullContext context = FullContext.build();
AgentTaskContext newCtx = context.createWithSteps(newQuestion, null, priorSteps);

ChatGeneration result = agent.invoke(newQuestion, newCtx);
```

### 11.4 SubAgent 停止自动传播

```java
McpAgentExecutor master = McpAgentExecutor.builder(chainActor)
    .llm(masterLlm)
    .subAgent(researcher)    // SubAgent 内部也会感知 master.stop()
    .build();

master.stop();               // 信号自动传入 SubAgent 的循环
```

---

## 12. 设计原则总结

| 原则 | 体现 |
|------|------|
| **跨线程安全** | AtomicBoolean 保证 stop() 和 shouldContinue() 跨线程可见性，不依赖 ThreadLocal |
| **侵入性最小** | Agent 执行主路径无任何 if-stop 分支，停止检查集中在 shouldContinue 一处 |
| **透明恢复** | Agent 不感知是首次还是恢复，initContext + applyPreloadedSteps 在链层面完成差异处理 |
| **信号透传** | SubAgent/Skill 主动从 ContextBus 读取并传入 externalSignal，单向透传，无反向耦合 |
| **控制流与错误分离** | FlowControlException 标记接口让框架区分"预期中断"与"真实错误"，避免误报日志 |
| **步骤完整性** | 检查点在工具完成后，不在工具内部，已执行完成的步骤不会被截断 |
| **可再次停止** | resume 时创建新 stopSignal，恢复后的执行可再次被 stop()，满足多次中断场景 |

---

## 13. 运行时停止类型扩展

本节记录在原有停止/继续机制之上新增的异常层次结构与三种运行时停止机制。

### 13.1 异常层次结构

```
AgentException（抽象类，实现 FlowControlException）
├── AgentStoppedException   — 不变，外部用户取消，携带 partialContext
├── AgentAbortException     — 系统强制终止
│     reason: AgentAbortReason 枚举 { MAX_STEPS, TIMEOUT, CONSECUTIVE_TOOL_FAILURES, BUDGET_EXCEEDED }
│     字段: AgentAbortReason reason, AgentTaskContext partialContext
└── AgentPauseException     — Agent 主动暂停（上层语义）
      字段: String reason, Map<String,Object> payload, AgentTaskContext partialContext
```

**设计理由**：

- `AgentStoppedException`：外部中断（用户调用 `stop()`），可恢复
- `AgentAbortException`：触发系统限制，由框架决策；`MAX_STEPS` 替代了原先在第 466 行抛出的裸 `RuntimeException`；所有情况均携带 `partialContext` 以供诊断
- `AgentPauseException`：Agent 语义层面的暂停（例如需要用户输入或审批）；`reason` 和 `payload` 由应用层定义，框架只提供机制；保存 `partialContext` 以供恢复

三种新异常类型均通过 `AgentException` 实现 `FlowControlException`，因此流程引擎以 DEBUG 级别记录，而非 WARN。

### 13.2 三种新运行时停止机制

#### 13.2.1 TIMEOUT（超时）

在 `shouldContinue` 中检查。当 `i==0` 时记录 `startTimeMs`。后续每次检查时，若已耗时 > `maxDurationMs`，则抛出 `AgentAbortException(TIMEOUT)`。

Builder 参数：`.maxDurationSeconds(int)`，0 表示禁用。

#### 13.2.2 CONSECUTIVE_TOOL_FAILURES（连续工具失败）

在 `executeTool` 闭包中维护 `AtomicInteger consecutiveFailures`：
- 任意工具调用失败（抛出异常或工具未找到）时递增
- 调用成功时重置为 0
- 达到 `maxConsecFail` 时抛出 `AgentAbortException(CONSECUTIVE_TOOL_FAILURES)`

**重要**：此计数统计的是 LLM 驱动的重试轮次（LLM 连续调用失败工具的次数），而非框架级重试。

Builder 参数：`.maxConsecutiveToolFailures(int)`，0 表示禁用。

#### 13.2.3 Framework Tool Retry（框架工具重试，toolRetry）

在将错误观测值返回给 LLM 之前，框架最多静默重试工具调用 `toolRetryCount` 次。只有全部尝试均失败后，错误信息才会作为 observation 传给 LLM。此过程对 LLM 透明。

Builder 参数：`.toolRetry(int)`，0 表示不重试。

### 13.3 关键设计原则

- **CONSECUTIVE_TOOL_FAILURES ≠ toolRetry**：前者计量 LLM 行为（LLM 连续多少轮调用失败工具）；后者是框架在 LLM 感知之前静默重试。两者相互独立，分别控制。
- **AgentPauseException 有意保持精简**：框架定义机制（保存上下文、向上传播），不定义语义（"wait_user" 的含义由应用层决定）。
- 三种新异常类型均通过 `AgentException` 实现 `FlowControlException`，框架以 DEBUG 级别记录，不产生误报告警。

### 13.4 invoke() 捕获块

`McpAgentExecutor` 和 `AgentExecutor` 现在同时捕获三种异常：

```java
catch (AgentStoppedException e) { savePartial() + rethrow }
catch (AgentPauseException e)   { savePartial() + rethrow }
catch (AgentAbortException e)   { savePartial() + rethrow }
```

### 13.5 新增 Builder 方法

```java
.maxDurationSeconds(int)          // 0 = 禁用超时检查
.maxConsecutiveToolFailures(int)  // 0 = 禁用连续失败检查
.toolRetry(int)                   // 0 = 不重试
```

### 13.6 涉及改动的文件

```
core/agent/
  AgentException.java               # 新增：抽象基类，实现 FlowControlException
  AgentAbortReason.java             # 新增：枚举 { MAX_STEPS, TIMEOUT, CONSECUTIVE_TOOL_FAILURES, BUDGET_EXCEEDED }
  AgentAbortException.java          # 新增：系统强制终止异常
  AgentPauseException.java          # 新增：Agent 主动暂停异常
  AgentStoppedException.java        # 修改：改为继承 AgentException
  McpAgentExecutor.java             # 修改：新增 Builder 参数、shouldContinue 超时检查、
                                    #       executeTool 重试+连续失败计数、invoke 捕获块
  AgentExecutor.java                # 修改：invoke 新增捕获 AgentPauseException/AgentAbortException
```
