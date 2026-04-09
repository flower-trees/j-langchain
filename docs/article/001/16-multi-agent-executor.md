# 双 Agent 串联：用分析 Agent + 执行 Agent 构建客服工单处理流水线

> **标签**：`Java` `Agent` `ReAct` `MCP` `j-langchain` `多Agent` `工单处理` `AgentExecutor`  
> **前置阅读**：[AgentExecutor 嵌入 Chain：让 Agent 成为流水线中的一个节点](15-agent-executor-embed.md)  
> **适合人群**：已掌握单 Agent 用法，希望构建多 Agent 协作流程的 Java 开发者

---

## 一、单 Agent 的边界在哪里

上篇文章展示了 `AgentExecutor` 嵌入 Chain 的基本用法：一个 Agent 配一组工具，完成一类任务。这个模式能覆盖大多数场景，但有一类任务它处理起来比较吃力——

**"决策"和"执行"需要完全不同的工具，而且两者之间有明确的移交关系。**

以客服工单处理为例：

- **分析阶段**：查订单详情、查物流状态、核对退款政策、判断退款资格——这些是纯粹的查询和推理，工具都是业务数据接口
- **执行阶段**：把处理结论写入工单系统、记录操作日志、读取文件确认——这些是 I/O 操作，工具是文件系统或数据库

如果把这两类工具都塞给同一个 Agent，工具列表会变得混乱，模型可能在"还没分析完"的时候就开始写文件，或者分析完之后又去重复查询。

更合理的设计是：**两个 Agent，各司其职，分析 Agent 的输出作为执行 Agent 的输入**。

---

## 二、整体架构

```
用户投诉
     ↓
TranslateHandler（预处理：格式化为分析任务）
     ↓
AgentExecutor（分析 Agent：ReAct + 业务工具）
     ↓ 分析结论
TranslateHandler（移交处理：把结论组装成执行指令）
     ↓
McpAgentExecutor（执行 Agent：Function Calling + filesystem）
     ↓ 执行确认
TranslateHandler（后处理：生成最终客服回复）
     ↓
最终输出
```

两个 Agent 的职责完全不同，连底层机制都不一样：分析 Agent 用 **ReAct（文本驱动）**，执行 Agent 用 **Function Calling（结构化输出）**。中间的 `TranslateHandler` 节点承担"翻译官"的角色，把分析 Agent 的自然语言输出转化为执行 Agent 能理解的结构化指令。

---

## 三、工具定义：分析 Agent 的四个工具

分析阶段需要四类能力，设计成独立工具便于模型逐步调用：

```java
static class OrderTools {

    // 查订单基础信息
    @AgentTool("查询订单详情")
    public String getOrderDetail(@Param("订单号，格式如 ORD-2024-001") String orderId) {
        return switch (orderId) {
            case "ORD-2024-001" -> "订单 ORD-2024-001：iPhone 15 Pro，¥8999，2024-03-10下单，已签收";
            case "ORD-2024-002" -> "订单 ORD-2024-002：笔记本电脑，¥5999，2024-03-12下单，运输中";
            default -> "订单 " + orderId + " 不存在";
        };
    }

    // 查物流轨迹
    @AgentTool("查询订单物流状态")
    public String getLogisticsStatus(@Param("订单号，格式如 ORD-2024-001") String orderId) {
        return switch (orderId) {
            case "ORD-2024-001" -> "物流状态：已于2024-03-13签收，签收人：本人，快递员：张师傅";
            case "ORD-2024-002" -> "物流状态：2024-03-14 到达上海转运中心，预计明日送达";
            default -> "未找到物流记录";
        };
    }

    // 查退款政策
    @AgentTool("查询退款政策")
    public String getRefundPolicy(@Param("商品类别，如：手机、电脑") String category) {
        return switch (category) {
            case "手机" -> "手机退款政策：7天无理由退货，15天质量问题换货，需保持原包装完整";
            case "电脑" -> "电脑退款政策：7天无理由退货，30天内质量问题免费维修或换货";
            default -> category + "退款政策：7天无理由退货，需保持商品完好";
        };
    }

    // 综合判断退款资格（调用前三个工具收集信息后再调用这个）
    @AgentTool("判断投诉是否符合退款条件")
    public String checkRefundEligibility(
            @Param("订单号") String orderId,
            @Param("投诉原因") String reason,
            @Param("距签收天数") String daysSinceReceived) {
        int days = Integer.parseInt(daysSinceReceived.replaceAll("[^0-9]", ""));
        if (days <= 7) {
            return String.format("订单 %s 符合退款条件：%s，距签收 %d 天，在7天无理由退货期内。建议全额退款 ¥8999。",
                orderId, reason, days);
        } else if (days <= 15 && reason.contains("质量")) {
            return String.format("订单 %s 符合换货条件：%s，距签收 %d 天，在15天质量问题换货期内。建议安排换货。",
                orderId, reason, days);
        } else {
            return String.format("订单 %s 不符合退款条件：距签收已 %d 天，超出退货期限。建议转人工处理。",
                orderId, days);
        }
    }
}
```

工具的设计有一个值得注意的地方：前三个工具负责**收集信息**，最后一个工具 `checkRefundEligibility` 负责**综合判断**。把判断逻辑封装进工具而不是依赖模型自己推算，有两个好处：判断规则完全由代码控制，不受模型版本影响；规则变更时只改工具实现，不需要修改 Prompt。

---

## 四、构建双 Agent 流水线

```java
@Test
public void dualAgentChain() {

    // ── Agent1：分析 Agent（ReAct 模式，使用业务工具）──────────────────
    AgentExecutor analysisAgent = AgentExecutor.builder(chainActor)
        .llm(ChatAliyun.builder().model("qwen3.6-plus").temperature(0f).build())
        .tools(new OrderTools())
        .maxIterations(10)  // 4个工具，每次调用对应一次 Thought，留足余量
        .onThought(t -> System.out.println("[分析] " + t))
        .onObservation(obs -> System.out.println("[查询结果] " + obs))
        .build();

    // ── Agent2：执行 Agent（Function Calling 模式，使用 filesystem）───
    McpAgentExecutor executionAgent = McpAgentExecutor.builder(chainActor)
        .llm(ChatAliyun.builder().model("qwen3.6-plus").temperature(0f).build())
        .tools(mcpClient, "filesystem")
        .systemPrompt(
            "你是一个工单处理系统，负责将处理结论写入文件并读取确认。" +
            "完成所有文件操作后，输出操作完成的确认信息，不要再调用任何工具。")
        .maxIterations(6)   // write_file + read_file = 2次，设 6 足够
        .onToolCall(tc -> System.out.println("[执行] " + tc))
        .onObservation(obs -> System.out.println("[执行结果] " + obs))
        .build();

    // ── 流水线：5个节点，两个 Agent 各占一个节点 ───────────────────────
    FlowInstance workflowChain = chainActor.builder()

        // 节点1：预处理——把用户投诉格式化为分析任务指令
        .next(new TranslateHandler<>(complaint -> {
            System.out.println("\n========== 收到投诉工单 ==========");
            System.out.println(complaint);
            System.out.println("\n--- Agent1：开始分析投诉 ---");
            return "用户投诉内容：" + complaint +
                "\n请你：1)查询订单详情和物流状态；2)查询适用的退款政策；" +
                "3)判断是否符合退款条件；4)给出明确的处理建议（批准退款/换货/拒绝）和理由。";
        }))

        // 节点2：分析 Agent——多轮工具调用，输出处理结论
        .next(analysisAgent)

        // 节点3：移交处理——把分析结论组装成执行 Agent 的指令
        .next(new TranslateHandler<String, ChatGeneration>(generation -> {
            String analysis = generation.getText();
            System.out.println("\n--- Agent1 分析完成，移交 Agent2 执行 ---");
            System.out.println("分析结论：" + analysis);
            System.out.println("\n--- Agent2：开始执行处理操作 ---");
            return "请将以下处理记录写入文件 /tmp/ticket_result.txt，" +
                "然后读取文件内容确认写入成功：\n\n" +
                "=== 工单处理记录 ===\n" +
                "处理时间：" + LocalDateTime.now() + "\n" +
                "处理结论：\n" + analysis;
        }))

        // 节点4：执行 Agent——写文件并读取确认
        .next(executionAgent)

        // 节点5：后处理——生成最终客服回复
        .next(new TranslateHandler<String, ChatGeneration>(generation ->
            "\n========== 工单处理完成 ==========\n" +
            "处理记录已保存至 /tmp/ticket_result.txt\n" +
            "客服系统处理结果：" + generation.getText() +
            "\n==================================="
        ))

        .build();

    // 执行
    String finalReply = chainActor.invoke(workflowChain, Map.of(
        "input",
        "我在3月10日购买了 iPhone 15 Pro（订单号 ORD-2024-001），" +
        "收到后发现屏幕有亮点，距签收才3天，要求退款。"
    ));

    System.out.println(finalReply);
}
```

---

## 五、完整执行过程

**阶段一：收到投诉**
```
========== 收到投诉工单 ==========
我在3月10日购买了 iPhone 15 Pro（订单号 ORD-2024-001），收到后发现屏幕有亮点，距签收才3天，要求退款。

--- Agent1：开始分析投诉 ---
```

**阶段二：分析 Agent 推理（节选）**
```
[分析] Thought: 先查询订单详情确认购买信息。
Action: get_order_detail
Action Input: {"orderId": "ORD-2024-001"}
[查询结果] 订单 ORD-2024-001：iPhone 15 Pro，¥8999，2024-03-10下单，已签收

[分析] Thought: 再查物流确认签收时间。
Action: get_logistics_status
Action Input: {"orderId": "ORD-2024-001"}
[查询结果] 物流状态：已于2024-03-13签收，签收人：本人

[分析] Thought: 查询手机退款政策。
Action: get_refund_policy
Action Input: {"category": "手机"}
[查询结果] 手机退款政策：7天无理由退货，15天质量问题换货，需保持原包装完整

[分析] Thought: 综合判断退款资格。距签收3天，在7天无理由退货期内。
Action: check_refund_eligibility
Action Input: {"orderId": "ORD-2024-001", "reason": "屏幕有亮点", "daysSinceReceived": "3"}
[查询结果] 订单 ORD-2024-001 符合退款条件：屏幕有亮点，距签收3天，在7天无理由退货期内。建议全额退款 ¥8999。

Final Answer: 经核查，订单 ORD-2024-001 符合退款条件，建议批准全额退款 ¥8999。
```

**阶段三：Agent 间移交**
```
--- Agent1 分析完成，移交 Agent2 执行 ---
分析结论：经核查，订单 ORD-2024-001 符合退款条件，建议批准全额退款 ¥8999。

--- Agent2：开始执行处理操作 ---
```

**阶段四：执行 Agent 操作**
```
[执行] write_file -> {"path": "/tmp/ticket_result.txt", "content": "=== 工单处理记录 ===\n..."}
[执行结果] 写入成功

[执行] read_file -> {"path": "/tmp/ticket_result.txt"}
[执行结果] === 工单处理记录 ===\n处理时间：2024-03-16T14:23:01\n处理结论：...
```

**阶段五：最终输出**
```
========== 工单处理完成 ==========
处理记录已保存至 /tmp/ticket_result.txt
客服系统处理结果：文件写入确认成功，工单处理记录已完整保存。
===================================
```

---

## 六、移交节点的设计：最容易被忽略的关键

两个 Agent 之间的 `TranslateHandler`（节点3）是整个流水线中最容易被低估的部分，但它的质量直接决定执行 Agent 能不能正确工作。

**如果移交节点写得过于简单：**
```java
// 糟糕的写法：把分析结论直接传给执行 Agent
return generation.getText();
```
执行 Agent 拿到的是一段自然语言，不知道要写到哪个文件、文件内容是什么格式、写完之后要做什么确认。模型只能猜，猜错的概率很高。

**正确的写法是把执行任务描述清楚：**
```java
return "请将以下处理记录写入文件 /tmp/ticket_result.txt，" +
    "然后读取文件内容确认写入成功：\n\n" +
    "=== 工单处理记录 ===\n" +
    "处理时间：" + LocalDateTime.now() + "\n" +   // 在 Java 层生成时间戳，不依赖模型
    "处理结论：\n" + analysis;
```

移交节点应该承担三件事：**提取上游的关键输出**、**补充下游需要的上下文**（如文件路径、时间戳等）、**明确描述下游的任务目标**。这些信息放在 Java 代码里生成，比让模型自己决定更可靠。

---

## 七、关于两种 Agent 类型的说明

细心的读者会发现，分析 Agent 用的是 `AgentExecutor`（ReAct 模式），执行 Agent 用的是 `McpAgentExecutor`（Function Calling 模式）。

这里需要说明一下：**这个选择主要是出于演示目的**，在一个示例里同时展示这两种 Agent 类型的用法。在实际项目中，两个 Agent 完全可以都用 `McpAgentExecutor`，这也是更常见的做法——只需要把分析 Agent 的工具来源从 `@AgentTool` 注解切换到 `McpManager`，架构和流水线逻辑完全不需要改动：

```java
// 实际项目中更常见的写法：两个 Agent 都用 McpAgentExecutor
McpAgentExecutor analysisAgent = McpAgentExecutor.builder(chainActor)
    .llm(ChatAliyun.builder().model("qwen3.6-plus").temperature(0f).build())
    .tools(mcpManager, "order")       // 从 mcp.config.json 加载订单查询工具
    .systemPrompt("你是客服分析助手，请查询订单信息并给出处理建议。")
    .maxIterations(10)
    .build();

McpAgentExecutor executionAgent = McpAgentExecutor.builder(chainActor)
    .llm(ChatAliyun.builder().model("qwen3.6-plus").temperature(0f).build())
    .tools(mcpClient, "filesystem")   // NPX filesystem 工具
    .systemPrompt("你是工单处理系统，负责将结论写入文件并确认。")
    .maxIterations(6)
    .build();
```

回到本例的演示意图：`AgentExecutor` 适合工具以 `@AgentTool` 注解方式定义（直接写在 Java 类里，适合快速原型和内部工具）；`McpAgentExecutor` 适合工具来自配置文件（`mcp.config.json` 或 `mcp.server.config.json`，适合标准化工具和团队共享）。两种方式都能嵌入 Chain 节点，**选哪种取决于工具的管理方式，而不是任务本身的性质**。

---

## 八、这个模式能扩展到哪些场景

双 Agent 串联的本质是**职责分离**：一个 Agent 负责"想清楚"，另一个负责"做到位"。这个结构在很多业务场景中都适用：

| 场景 | 分析 Agent 的任务 | 执行 Agent 的任务 |
|---|---|---|
| 客服工单 | 查订单、核政策、给建议 | 写工单系统、发通知 |
| 代码审查 | 分析代码问题、生成修改建议 | 写入文件、提交 Git |
| 数据报表 | 查多个数据源、计算指标 | 写入 Excel、发邮件 |
| 采购审批 | 查库存、核预算、判断合规 | 写审批记录、更新 ERP |
| 运维巡检 | 查服务状态、判断异常 | 写日志、触发告警 |

如果执行阶段需要操作更多系统（如同时写数据库和发消息队列），只需在执行 Agent 上多挂一个 `McpClient` 工具组，其余不变。

---

## 九、总结

本篇展示的双 Agent 串联是 j-langchain 链式编排能力的一个典型用法：**Chain 作为编排容器，Agent 作为其中的执行节点，TranslateHandler 作为节点间的数据转换层**。

三者各司其职：Chain 管流转，Agent 管推理，Handler 管格式——这种分层设计让复杂的 AI 工作流变得清晰可维护，每个层次都可以独立修改而不影响其他部分。

---

> 📎 相关资源
> - 完整示例：[Article16CustomerService.java](../../../src/test/java/org/salt/jlangchain/demo/article/Article16CustomerService.java)，对应方法 `dualAgentChain()`
> - j-langchain GitHub：https://github.com/flower-trees/j-langchain
> - j-langchain Gitee 镜像：https://gitee.com/flower-trees-z/j-langchain
> - 运行环境：需配置阿里云 API Key，示例模型 `qwen3.6-plus`；需本地安装 Node.js 以运行 npx