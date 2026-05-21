# Human-in-the-Loop：Agent 执行中途等待用户确认

> **标签**：`Java` `AgentPauseException` `Human-in-the-Loop` `工具语义分离` `LLM路由` `partialContext` `addHumanTurn` `j-langchain`  
> **前置阅读**：[Agent 运行时停止类型：MAX_STEPS、TIMEOUT、CONSECUTIVE_TOOL_FAILURES 与 AgentPauseException](26-agent-stop-types.md)  
> **适合人群**：需要在 Agent 自动执行流程中插入人工确认节点的 Java 开发者

---

## 一、问题背景

Agent 的核心价值是**自主执行**：用户只需说出意图，Agent 自动调用工具完成任务。但"完全自主"并不总是正确的——有一类操作天然需要人类决策介入：

| 操作类型 | 不可逆程度 | 为什么需要人工确认 |
|----------|-----------|-----------------|
| 银行转账 | 极高 | 资金一旦转出难以追回 |
| 批量删除文件 | 高 | 覆盖或误删无法恢复 |
| 数据库批量更新 | 高 | 线上数据污染成本极高 |
| 发送邮件/通知 | 中 | 发出后无法撤回，影响他人 |
| 生产环境部署 | 高 | 错误版本影响线上用户 |

传统做法是在代码里硬编码"到某个节点就 return，等外部注入新参数再重新调用"，但这与 Agent 的推理循环高度耦合，改动成本大，也难以处理"Agent 已完成若干步骤、不希望重复执行"的场景。

j-langchain 提供了一套更优雅的方案：**工具语义分离 + AgentPauseException + partialContext 跳步恢复**。

---

## 二、设计思路：工具语义分离

核心思想是把一个"危险操作"拆分成三个语义明确的工具：

```
request_transfer   →   发起请求（必然暂停，等待人工确认）
confirm_transfer   →   确认执行（用户批准后真正执行）
cancel_transfer    →   取消操作（用户拒绝后清理）
```

加上前置的信息收集工具（如 `check_balance`），整个流程由 4 个工具协同完成：

| 工具 | 是否暂停 | 职责 |
|------|---------|------|
| `check_balance` | 否 | 自动执行，收集决策所需信息 |
| `request_transfer` | **是** | 发起请求，携带确认信息抛出 `AgentPauseException` |
| `confirm_transfer` | 否 | 用户批准后由 LLM 主动调用，执行真正操作 |
| `cancel_transfer` | 否 | 用户拒绝后由 LLM 主动调用，安全取消 |

这种分离的关键是：**框架不需要知道"用户批准还是拒绝"的业务逻辑**。用户的决定通过 `addHumanTurn` 追加为对话中的新一轮 human 消息，LLM 根据上下文自主选择调用哪个工具。

---

## 三、完整流程图

```
用户: "先查询余额，确认充足后向张三转账 50000 元"
         │
         ▼
┌─────────────────────────────────────────────────────────────┐
│  第一次 invoke(question)                                     │
│                                                             │
│  LLM 推理 → 调用 check_balance(account=...)                 │
│    ✓ 工具返回："账户余额：¥80,000，可用余额充足"              │
│    → step-1 记录到 partialContext                           │
│                                                             │
│  LLM 推理 → 调用 request_transfer(amount=50000, to=张三)     │
│    ! 工具内 throw AgentPauseException(                      │
│         reason="need_confirmation",                         │
│         payload={"action": "向张三转账 ¥50000"},             │
│         ctx=当前执行上下文)                                   │
│    框架捕获，记录 step-2（含合成观测 "[paused: ...]"），      │
│    向上传播异常                                              │
└─────────────────────────────────────────────────────────────┘
         │
         ▼  调用方 catch AgentPauseException
         │
         │  展示给用户:
         │    操作: 向张三转账 ¥50000
         │    已完成步骤数: 2（余额查询 + 转账请求已记录）
         │
         ▼  用户输入 y 或 n
         │
┌─────────────────────────────────────────────────────────────┐
│  第二次 invoke("y" 或 "n",                                   │
│               paused.getPartialContext())                    │
│                                                             │
│  框架将用户输入追加为对话末尾的新 human 轮次（addHumanTurn）  │
│  LLM 看到的完整消息链：                                      │
│    human:  原始问题                                          │
│    assistant: check_balance(...)                             │
│    tool:   余额充足                                          │
│    assistant: request_transfer(...)                          │
│    tool:   [paused: need_confirmation]                       │
│    human:  y  ← 出现在暂停点之后，时序正确                   │
│                                                             │
│  用户决定 = "y"                   用户决定 = "n"            │
│    ↓                                ↓                       │
│  调用 confirm_transfer            调用 cancel_transfer       │
│    → "转账成功：已向张三转账         → "转账已取消：           │
│        ¥50000"                         用户拒绝了本次操作"   │
└─────────────────────────────────────────────────────────────┘
```

---

## 四、工具设计详解

### 4.1 check_balance：自动执行，无需确认

```java
Tool balanceTool = Tool.builder()
        .name("check_balance")
        .description("查询账户当前余额")
        .params("account: String")
        .func(args -> "账户余额：¥80,000，可用余额充足")
        .build();
```

普通工具，正常返回。执行结果记录为 step-1，下次带 `partialContext` 恢复时直接跳过，**不会再次查询余额**。

---

### 4.2 request_transfer：发起请求，必然暂停

```java
Tool requestTransferTool = Tool.builder()
        .name("request_transfer")
        .description("发起转账请求，系统会暂停并等待用户确认后才能真正执行")
        .params("amount: String, to: String")
        .func(args -> {
            @SuppressWarnings("unchecked")
            Map<String, Object> map = (Map<String, Object>) args;

            // 从 ContextBus 获取当前 Agent 执行上下文
            AgentTaskContext ctx = ContextBus.get()
                    .getTransmit(CallInfo.AGENT_TASK_CTX.name());

            // 无论如何都暂停，携带操作说明作为 payload
            throw new AgentPauseException(
                    "need_confirmation",
                    Map.of("action", "向 " + map.get("to") + " 转账 ¥" + map.get("amount")),
                    ctx);
        })
        .build();
```

关键点：
- `ContextBus.get().getTransmit(CallInfo.AGENT_TASK_CTX.name())` 获取**当前时刻**的执行上下文，包含已完成的所有步骤
- `payload` 是展示给用户的结构化说明，可以包含任何业务数据
- 这个工具**总是抛出异常**，它的职责只是"请求暂停"，不执行任何真正的业务操作
- **框架行为**：捕获到 `AgentPauseException` 时，会把这次工具调用以合成观测 `[paused: need_confirmation]` 记录进 `partialContext`，确保 LLM 在 resume 时能看到完整的调用历史

---

### 4.3 confirm_transfer：用户批准后执行

```java
Tool confirmTransferTool = Tool.builder()
        .name("confirm_transfer")
        .description("用户已批准后调用此工具，执行真正的转账")
        .params("amount: String, to: String")
        .func(args -> {
            @SuppressWarnings("unchecked")
            Map<String, Object> map = (Map<String, Object>) args;
            return "转账成功：已向 " + map.get("to") + " 转账 ¥" + map.get("amount");
        })
        .build();
```

这是真正执行业务操作的工具。**LLM 不会在第一次调用时选择它**——因为根据工具描述，它需要"用户已批准"这个前提条件。只有在 resume 时，用户输入 `y` 作为新 human 轮次被追加，LLM 才会路由到这里。

---

### 4.4 cancel_transfer：用户拒绝后取消

```java
Tool cancelTransferTool = Tool.builder()
        .name("cancel_transfer")
        .description("用户已拒绝后调用此工具，取消本次转账")
        .params("reason: String")
        .func(args -> "转账已取消：用户拒绝了本次操作")
        .build();
```

同理，用户输入 `n` 时，LLM 看到 `n` 作为最后的 human 轮次，路由到此工具。

---

## 五、完整代码

### 5.1 Agent 构建

```java
McpAgentExecutor agent = McpAgentExecutor.builder(chainActor)
        .llm(ChatAliyun.builder().model("qwen-plus").temperature(0f).build())
        .tools(balanceTool, requestTransferTool, confirmTransferTool, cancelTransferTool)
        .verbose(true)
        .build();
```

只需一个 Agent，包含全部 4 个工具。与 `testPauseAndResume`（文章 26）中"审批通过后需要换工具重建 Agent"的方案不同，这里 resume 仍然使用同一个 `agent` 实例——因为工具本身不换，LLM 通过上下文自主判断该调哪个。

---

### 5.2 第一次执行：自动跑到确认节点

```java
String question = "先查询我的账户余额，确认余额充足后帮我向张三转账 50000 元";

AgentPauseException paused = null;
try {
    agent.invoke(question);
} catch (AgentPauseException e) {
    paused = e;
}

// 展示给用户
System.out.println(">>> 需要用户确认");
System.out.println("    操作: " + paused.getPayload().get("action"));
// 输出：操作: 向张三转账 ¥50000

System.out.println("    已完成步骤数: " + paused.getCompletedSteps().size());
// 输出：已完成步骤数: 2
// step-1: check_balance（余额查询）
// step-2: request_transfer（含合成观测 "[paused: need_confirmation]"）
```

---

### 5.3 收集用户输入，恢复执行

```java
// 生产环境：String userInput = new Scanner(System.in).nextLine();
String userInput = "y"; // 模拟用户输入

// 直接把用户输入作为新 invoke 的 question，框架会将其追加为对话末尾的 human 轮次
ChatGeneration result = agent.invoke(userInput, paused.getPartialContext());

System.out.println(">>> 执行结果（用户输入 " + userInput + "）");
System.out.println(result.getText());
// y → 转账成功：已向张三转账 ¥50000
// n → 转账已取消：用户拒绝了本次操作
```

框架内部构建给 LLM 的消息链：

```
human:     先查询我的账户余额...（原始请求）
assistant: [tool_call: check_balance]
tool:      账户余额：¥80,000，可用余额充足
assistant: [tool_call: request_transfer]
tool:      [paused: need_confirmation]
human:     y                              ← 用户决定，出现在暂停点之后
```

这一顺序让 LLM 能清楚地推理：转账请求已发起、处于等待确认状态，用户刚刚决定批准 → 应调用 `confirm_transfer`。

---

### 5.4 完整测试代码（两个方向）

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
    String question = "先查询我的账户余额，确认余额充足后帮我向张三转账 50000 元";

    Tool balanceTool = Tool.builder()
            .name("check_balance")
            .description("查询账户当前余额")
            .params("account: String")
            .func(args -> "账户余额：¥80,000，可用余额充足")
            .build();

    Tool requestTransferTool = Tool.builder()
            .name("request_transfer")
            .description("发起转账请求，系统会暂停并等待用户确认后才能真正执行")
            .params("amount: String, to: String")
            .func(args -> {
                @SuppressWarnings("unchecked")
                Map<String, Object> map = (Map<String, Object>) args;
                AgentTaskContext ctx = ContextBus.get()
                        .getTransmit(CallInfo.AGENT_TASK_CTX.name());
                throw new AgentPauseException(
                        "need_confirmation",
                        Map.of("action", "向 " + map.get("to") + " 转账 ¥" + map.get("amount")),
                        ctx);
            })
            .build();

    Tool confirmTransferTool = Tool.builder()
            .name("confirm_transfer")
            .description("用户已批准后调用此工具，执行真正的转账")
            .params("amount: String, to: String")
            .func(args -> {
                @SuppressWarnings("unchecked")
                Map<String, Object> map = (Map<String, Object>) args;
                return "转账成功：已向 " + map.get("to") + " 转账 ¥" + map.get("amount");
            })
            .build();

    Tool cancelTransferTool = Tool.builder()
            .name("cancel_transfer")
            .description("用户已拒绝后调用此工具，取消本次转账")
            .params("reason: String")
            .func(args -> "转账已取消：用户拒绝了本次操作")
            .build();

    McpAgentExecutor agent = McpAgentExecutor.builder(chainActor)
            .llm(ChatAliyun.builder().model("qwen-plus").temperature(0f).build())
            .tools(balanceTool, requestTransferTool, confirmTransferTool, cancelTransferTool)
            .verbose(true)
            .build();

    // ── 第一次 invoke：查余额后，发起转账时暂停 ─────────────────────────
    AgentPauseException paused = null;
    try {
        agent.invoke(question);
    } catch (AgentPauseException e) {
        paused = e;
    }

    System.out.println("\n>>> 需要用户确认");
    System.out.println("    操作: " + paused.getPayload().get("action"));
    System.out.println("    暂停前已完成步骤数: " + paused.getCompletedSteps().size());

    // ── 模拟用户输入，直接作为新 human 轮次传入 ─────────────────────────
    // 生产环境：String userInput = new Scanner(System.in).nextLine();
    System.out.print("确认执行以上操作？(y/n): ");
    System.out.println(simulatedInput + "  ← 模拟输入");

    var result = agent.invoke(simulatedInput, paused.getPartialContext());

    System.out.println("\n>>> 执行结果（用户输入 " + simulatedInput + "）");
    System.out.println(result.getText());
}
```

**运行输出（y 分支）**：

```
>>> 需要用户确认
    操作: 向 张三 转账 ¥50000
    暂停前已完成步骤数: 2
确认执行以上操作？(y/n): y  ← 模拟输入

>>> 执行结果（用户输入 y）
转账成功：已向张三转账 ¥50000
```

**运行输出（n 分支）**：

```
>>> 需要用户确认
    操作: 向 张三 转账 ¥50000
    暂停前已完成步骤数: 2
确认执行以上操作？(y/n): n  ← 模拟输入

>>> 执行结果（用户输入 n）
转账已取消：用户拒绝了本次操作
```

---

## 六、关键设计：为什么让 LLM 路由而不是框架层硬切工具

一种看似更简单的设计是：在 resume 时由框架直接决定调用哪个工具——如果 `userInput = "y"` 就调 `confirm_transfer`，否则调 `cancel_transfer`。

这种方式**不采用**，原因如下：

### 6.1 LLM 能理解语义，不只是 y/n

用户可能输入：
- `"y, 但金额改成 30000 元"` → LLM 用新金额调用 `confirm_transfer`
- `"先查一下对方账户是否正确，再确认"` → LLM 先调查询工具，再确认
- `"n, 明天再转"` → LLM 调用 `cancel_transfer`，可附带原因

框架层无法解析这些语义，只能做字符串比较。LLM 却能理解意图并相应调整后续行为。

### 6.2 工具数量不只两个

实际场景中，resume 后可能有多种后续路径（部分批准、修改参数、请求额外信息等），框架层的 if-else 会迅速膨胀。工具描述天然是 LLM 的路由表——新增工具只需添加描述，框架不感知。

### 6.3 代码更简洁

框架层的职责只有一件事：**把用户输入追加为新 human 轮次，把 `partialContext` 中的已完成步骤注入对话历史，让 LLM 继续推理**。业务路由逻辑完全在工具描述和 LLM 的理解里，框架保持无业务逻辑。

---

## 七、关键设计：partialContext 与 addHumanTurn

### 7.1 partialContext 携带的信息

`AgentPauseException.getPartialContext()` 返回的是一个 `AgentTaskContext`，包含：

- **已完成的步骤列表**（`completedSteps`）：包含 LLM 的每轮工具调用及其观测结果
- 暂停触发工具（`request_transfer`）的合成步骤，观测值为 `"[paused: need_confirmation]"`

本例中暂停时有 **2 个步骤**：
```
step-1: check_balance 调用 + "账户余额：¥80,000，可用余额充足"
step-2: request_transfer 调用 + "[paused: need_confirmation]"
```

### 7.2 resume 时的消息注入

当 `agent.invoke("y", savedCtx)` 被调用时，框架执行以下操作：
1. 从 `partialContext` 中加载步骤，注入对话历史
2. 将 `"y"` 通过 `addHumanTurn` 追加为最后一条 human 消息
3. LLM 看到完整且时序正确的上下文后推理下一步

**两次调用不会重复执行**：step-1 中已有余额结果，LLM 不会再调 `check_balance`；step-2 表明转账请求已发起，用户的决定 `y` 出现在正确的时间位置。

---

## 八、扩展场景

Human-in-the-Loop 模式不局限于转账，任何涉及不可逆操作的场景都适用：

### 8.1 文件批量删除

```java
// 扫描工具：自动列出待删除文件
Tool scanFilesTool = Tool.builder()
        .name("scan_files_to_delete")
        .description("扫描指定目录，返回待删除的文件列表")
        .params("directory: String, pattern: String")
        .func(args -> "找到 47 个 .log 文件，共 2.3 GB")
        .build();

// 请求删除工具：暂停等待确认
Tool requestDeleteTool = Tool.builder()
        .name("request_batch_delete")
        .description("发起批量删除请求，系统会暂停等待用户确认")
        .params("directory: String, pattern: String, count: String")
        .func(args -> {
            AgentTaskContext ctx = ContextBus.get()
                    .getTransmit(CallInfo.AGENT_TASK_CTX.name());
            throw new AgentPauseException(
                    "need_confirmation",
                    Map.of("action", "删除 " + ((Map<?,?>) args).get("count") + " 个日志文件"),
                    ctx);
        })
        .build();
```

### 8.2 数据库批量更新

```java
// 预览工具：显示将被影响的行数
Tool previewUpdateTool = Tool.builder()
        .name("preview_bulk_update")
        .description("预览批量更新操作，返回将受影响的记录数")
        .params("table: String, condition: String, new_value: String")
        .func(args -> "将更新 1,234 行记录")
        .build();

// 请求更新工具：暂停等待 DBA 确认
Tool requestUpdateTool = Tool.builder()
        .name("request_bulk_update")
        .description("发起数据库批量更新请求，等待 DBA 确认")
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

### 8.3 通用模板

任何场景都遵循同一套模式：

```
收集信息的工具（自动）
    → 发起请求的工具（暂停）
    → 执行确认的工具（用户批准后 LLM 自动路由）
    → 取消操作的工具（用户拒绝后 LLM 自动路由）
```

工具描述的语言就是路由规则，LLM 根据上下文自动推理该走哪条分支。

---

## 九、运行前置条件

1. **`ALIYUN_KEY`** 环境变量：示例使用 `qwen-plus`，需配置通义千问 API Key
2. 无需额外资源文件，所有工具在代码中构造
3. 两个测试方法（`testUserConfirmYes` / `testUserConfirmNo`）均直接可运行

---

## 十、总结

Human-in-the-Loop 在 j-langchain 中的实现方式，体现了三个设计原则：

**工具语义分离**：把"危险操作"分解为"发起请求"、"确认执行"、"取消操作"三个语义独立的工具。每个工具的职责单一、边界清晰，工具描述本身就是 LLM 的路由规则。

**AgentPauseException 携带执行现场**：工具在抛出异常时同步保存 `partialContext`，包含所有已完成步骤——包括暂停触发工具的合成观测记录。恢复时这些步骤直接注入对话历史，LLM 无需重新执行已完成的工作，也能清楚地看到暂停发生在哪里。

**addHumanTurn 保持时序正确**：用户的决定被追加为对话末尾的新 human 轮次，而不是拼入原始问题。这使得消息顺序与真实交互一致：原始请求 → 已完成步骤 → 用户决定。LLM 在这个完整、时序正确的上下文中自主选择下一步，框架不介入任何业务路由。

这三者共同实现了"Agent 自主执行 + 关键节点人工把关"的协作模式，让 Agent 既能发挥自动化的效率优势，又不会在不可逆操作上"越权行事"。

---

> 相关资源
> - 完整代码：[Article26AgentStopTypes.java](../../../src/test/java/org/salt/jlangchain/demo/article/Article26AgentStopTypes.java)（`testUserConfirmYes` / `testUserConfirmNo` / `doConfirmFlow` 方法）
> - 前置阅读：[Agent 运行时停止类型：MAX_STEPS、TIMEOUT、CONSECUTIVE_TOOL_FAILURES 与 AgentPauseException](26-agent-stop-types.md)
> - j-langchain GitHub：https://github.com/flower-trees/j-langchain
> - 运行环境：`ALIYUN_KEY`（`qwen-plus`）
