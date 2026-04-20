# Dual-Agent Pipeline: Building a Customer Service Ticket Workflow with an Analysis Agent + Execution Agent

> **Tags**: `Java` `Agent` `ReAct` `MCP` `j-langchain` `multi-agent` `ticket handling` `AgentExecutor`  
> **Prerequisite**: [Embedding AgentExecutor in a Chain: Making the Agent a Node in Your Pipeline](15-agent-executor-embed.md)  
> **Audience**: Java developers who know single-Agent usage and want to build multi-Agent collaborative workflows

---

## 1. Where the Single Agent Reaches Its Limit

The previous article showed the basic usage of embedding `AgentExecutor` in a Chain: one Agent, one set of tools, one type of task. This pattern covers most scenarios, but there's a class of tasks it handles poorly —

**"Decision-making" and "execution" require completely different tools, and there is a clear handoff relationship between them.**

Take customer service ticket handling as an example:

- **Analysis phase**: query order details, check logistics status, verify the refund policy, determine refund eligibility — these are pure queries and reasoning; the tools are business data interfaces
- **Execution phase**: write the processing conclusion to the ticket system, record the operation log, read the file to confirm — these are I/O operations; the tools are filesystem or database

If you give both types of tools to the same Agent, the tool list becomes messy, and the model might start writing files before finishing the analysis, or go back and query again after analysis is complete.

A better design: **two Agents, each with a clear role; the analysis Agent's output becomes the execution Agent's input**.

---

## 2. Overall Architecture

```
User complaint
     ↓
TranslateHandler (pre-process: format as analysis task)
     ↓
AgentExecutor (Analysis Agent: ReAct + business tools)
     ↓ analysis conclusion
TranslateHandler (handoff: assemble conclusion into execution instruction)
     ↓
McpAgentExecutor (Execution Agent: Function Calling + filesystem)
     ↓ execution confirmation
TranslateHandler (post-process: generate final customer service reply)
     ↓
Final output
```

The two Agents have completely different responsibilities — even the underlying mechanism differs: the Analysis Agent uses **ReAct (text-driven)**, the Execution Agent uses **Function Calling (structured output)**. The `TranslateHandler` node in the middle acts as a "translator", converting the Analysis Agent's natural language output into a structured instruction the Execution Agent can understand.

---

## 3. Tool Definitions: The Analysis Agent's Four Tools

The analysis phase needs four types of capability, designed as independent tools for the model to call step by step:

```java
static class OrderTools {

    // Query basic order information
    @AgentTool("查询订单详情")
    public String getOrderDetail(@Param("订单号，格式如 ORD-2024-001") String orderId) {
        return switch (orderId) {
            case "ORD-2024-001" -> "订单 ORD-2024-001：iPhone 15 Pro，¥8999，2024-03-10下单，已签收";
            case "ORD-2024-002" -> "订单 ORD-2024-002：笔记本电脑，¥5999，2024-03-12下单，运输中";
            default -> "订单 " + orderId + " 不存在";
        };
    }

    // Query logistics tracking
    @AgentTool("查询订单物流状态")
    public String getLogisticsStatus(@Param("订单号，格式如 ORD-2024-001") String orderId) {
        return switch (orderId) {
            case "ORD-2024-001" -> "物流状态：已于2024-03-13签收，签收人：本人，快递员：张师傅";
            case "ORD-2024-002" -> "物流状态：2024-03-14 到达上海转运中心，预计明日送达";
            default -> "未找到物流记录";
        };
    }

    // Query refund policy
    @AgentTool("查询退款政策")
    public String getRefundPolicy(@Param("商品类别，如：手机、电脑") String category) {
        return switch (category) {
            case "手机" -> "手机退款政策：7天无理由退货，15天质量问题换货，需保持原包装完整";
            case "电脑" -> "电脑退款政策：7天无理由退货，30天内质量问题免费维修或换货";
            default -> category + "退款政策：7天无理由退货，需保持商品完好";
        };
    }

    // Comprehensive eligibility check (called after the first three tools collect information)
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

There's a design point worth noting: the first three tools handle **information gathering**, and the last tool `checkRefundEligibility` handles **comprehensive judgment**. Encapsulating the judgment logic in a tool rather than relying on the model to infer it has two benefits: the decision rules are fully under code control and aren't affected by model version changes; when rules change, only the tool implementation needs updating, not the Prompt.

---

## 4. Building the Dual-Agent Pipeline

```java
@Test
public void dualAgentChain() {

    // ── Agent 1: Analysis Agent (ReAct mode, business tools) ──────────────────
    AgentExecutor analysisAgent = AgentExecutor.builder(chainActor)
        .llm(ChatAliyun.builder().model("qwen3.6-plus").temperature(0f).build())
        .tools(new OrderTools())
        .maxIterations(10)  // 4 tools, each with a Thought, leave headroom
        .onThought(t -> System.out.println("[分析] " + t))
        .onObservation(obs -> System.out.println("[查询结果] " + obs))
        .build();

    // ── Agent 2: Execution Agent (Function Calling mode, filesystem) ──────────
    McpAgentExecutor executionAgent = McpAgentExecutor.builder(chainActor)
        .llm(ChatAliyun.builder().model("qwen3.6-plus").temperature(0f).build())
        .tools(mcpClient, "filesystem")
        .systemPrompt(
            "你是一个工单处理系统，负责将处理结论写入文件并读取确认。" +
            "完成所有文件操作后，输出操作完成的确认信息，不要再调用任何工具。")
        .maxIterations(6)   // write_file + read_file = 2 calls; 6 is sufficient
        .onToolCall(tc -> System.out.println("[执行] " + tc))
        .onObservation(obs -> System.out.println("[执行结果] " + obs))
        .build();

    // ── Pipeline: 5 nodes, two Agents each occupying one node ─────────────────
    FlowInstance workflowChain = chainActor.builder()

        // Node 1: pre-process — format the user complaint as an analysis task instruction
        .next(new TranslateHandler<>(complaint -> {
            System.out.println("\n========== 收到投诉工单 ==========");
            System.out.println(complaint);
            System.out.println("\n--- Agent1：开始分析投诉 ---");
            return "用户投诉内容：" + complaint +
                "\n请你：1)查询订单详情和物流状态；2)查询适用的退款政策；" +
                "3)判断是否符合退款条件；4)给出明确的处理建议（批准退款/换货/拒绝）和理由。";
        }))

        // Node 2: Analysis Agent — multi-round tool calls, output processing conclusion
        .next(analysisAgent)

        // Node 3: handoff — assemble the analysis conclusion into the execution Agent's instruction
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

        // Node 4: Execution Agent — write file and read to confirm
        .next(executionAgent)

        // Node 5: post-process — generate the final customer service reply
        .next(new TranslateHandler<String, ChatGeneration>(generation ->
            "\n========== 工单处理完成 ==========\n" +
            "处理记录已保存至 /tmp/ticket_result.txt\n" +
            "客服系统处理结果：" + generation.getText() +
            "\n==================================="
        ))

        .build();

    // Execute
    String finalReply = chainActor.invoke(workflowChain, Map.of(
        "input",
        "我在3月10日购买了 iPhone 15 Pro（订单号 ORD-2024-001），" +
        "收到后发现屏幕有亮点，距签收才3天，要求退款。"
    ));

    System.out.println(finalReply);
}
```

---

## 5. Complete Execution Trace

**Phase 1: Receive the complaint**
```
========== 收到投诉工单 ==========
我在3月10日购买了 iPhone 15 Pro（订单号 ORD-2024-001），收到后发现屏幕有亮点，距签收才3天，要求退款。

--- Agent1：开始分析投诉 ---
```

**Phase 2: Analysis Agent reasoning (excerpt)**
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

**Phase 3: Agent handoff**
```
--- Agent1 分析完成，移交 Agent2 执行 ---
分析结论：经核查，订单 ORD-2024-001 符合退款条件，建议批准全额退款 ¥8999。

--- Agent2：开始执行处理操作 ---
```

**Phase 4: Execution Agent operations**
```
[执行] write_file -> {"path": "/tmp/ticket_result.txt", "content": "=== 工单处理记录 ===\n..."}
[执行结果] 写入成功

[执行] read_file -> {"path": "/tmp/ticket_result.txt"}
[执行结果] === 工单处理记录 ===\n处理时间：2024-03-16T14:23:01\n处理结论：...
```

**Phase 5: Final output**
```
========== 工单处理完成 ==========
处理记录已保存至 /tmp/ticket_result.txt
客服系统处理结果：文件写入确认成功，工单处理记录已完整保存。
===================================
```

---

## 6. The Handoff Node Design: The Most Overlooked Key

The `TranslateHandler` (Node 3) between the two Agents is the most underestimated part of the entire pipeline, but its quality directly determines whether the Execution Agent can work correctly.

**If the handoff node is written too simply:**
```java
// Poor approach: pass the analysis conclusion directly to the Execution Agent
return generation.getText();
```
The Execution Agent receives a block of natural language, with no idea which file to write, what format the content should be, or what confirmation to do after writing. The model can only guess, with a high probability of guessing wrong.

**The correct approach is to describe the execution task clearly:**
```java
return "请将以下处理记录写入文件 /tmp/ticket_result.txt，" +
    "然后读取文件内容确认写入成功：\n\n" +
    "=== 工单处理记录 ===\n" +
    "处理时间：" + LocalDateTime.now() + "\n" +   // Generate timestamp in Java, not relying on the model
    "处理结论：\n" + analysis;
```

The handoff node should handle three things: **extract key output from upstream**, **supplement context the downstream needs** (like file path, timestamp, etc.), and **explicitly describe the downstream's task goal**. Generating this information in Java code is more reliable than letting the model decide.

---

## 7. A Note on the Two Agent Types Used Here

Observant readers will notice the Analysis Agent uses `AgentExecutor` (ReAct mode) while the Execution Agent uses `McpAgentExecutor` (Function Calling mode).

This choice is mainly **for demonstration purposes** — to show both Agent types in one example. In real projects, both Agents can use `McpAgentExecutor`, which is actually the more common approach. Just switch the Analysis Agent's tool source from `@AgentTool` annotations to `McpManager`; the architecture and pipeline logic need no changes at all:

```java
// More common approach in real projects: both Agents use McpAgentExecutor
McpAgentExecutor analysisAgent = McpAgentExecutor.builder(chainActor)
    .llm(ChatAliyun.builder().model("qwen3.6-plus").temperature(0f).build())
    .tools(mcpManager, "order")       // Load order query tools from mcp.config.json
    .systemPrompt("你是客服分析助手，请查询订单信息并给出处理建议。")
    .maxIterations(10)
    .build();

McpAgentExecutor executionAgent = McpAgentExecutor.builder(chainActor)
    .llm(ChatAliyun.builder().model("qwen3.6-plus").temperature(0f).build())
    .tools(mcpClient, "filesystem")   // NPX filesystem tools
    .systemPrompt("你是工单处理系统，负责将结论写入文件并确认。")
    .maxIterations(6)
    .build();
```

To explain the demo's intent: `AgentExecutor` is suited for tools defined as `@AgentTool` annotations (written directly in Java classes, good for rapid prototyping and internal tools); `McpAgentExecutor` is suited for tools sourced from config files (`mcp.config.json` or `mcp.server.config.json`, good for standardized tools and team sharing). Both can be embedded as Chain nodes. **The choice depends on how tools are managed, not the nature of the task itself**.

---

## 8. Where This Pattern Generalizes

The essence of dual-Agent pipelines is **separation of concerns**: one Agent is responsible for "thinking it through," the other for "carrying it out." This structure applies to many business scenarios:

| Scenario | Analysis Agent's task | Execution Agent's task |
|---|---|---|
| Customer service ticket | Query orders, check policies, give recommendation | Write to ticket system, send notification |
| Code review | Analyze code issues, generate fix suggestions | Write to file, commit to Git |
| Data report | Query multiple data sources, compute metrics | Write to Excel, send email |
| Procurement approval | Check inventory, verify budget, assess compliance | Write approval record, update ERP |
| Operations inspection | Check service status, identify anomalies | Write log, trigger alert |

If the execution phase needs to operate more systems (e.g., simultaneously write to a database and publish to a message queue), just mount another `McpClient` tool group on the Execution Agent — nothing else changes.

---

## 9. Summary

The dual-Agent pipeline shown here is a typical usage of j-langchain's chain orchestration capabilities: **Chain as the orchestration container, Agent as the execution node within it, TranslateHandler as the data transformation layer between nodes**.

Each has its role: Chain manages the flow, Agent manages the reasoning, Handler manages the format — this layered design makes complex AI workflows clear and maintainable, with each layer independently modifiable without affecting the others.

---

> 📎 Resources
> - Full example: [Article16CustomerService.java](../../../src/test/java/org/salt/jlangchain/demo/article/Article16CustomerService.java), method `dualAgentChain()`
> - j-langchain GitHub: https://github.com/flower-trees/j-langchain
> - j-langchain Gitee mirror: https://gitee.com/flower-trees-z/j-langchain
> - Runtime requirements: Aliyun API Key required, example model `qwen3.6-plus`; Node.js must be installed locally to run npx
