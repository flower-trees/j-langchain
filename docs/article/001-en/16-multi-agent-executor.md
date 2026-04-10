# Dual-Agent Pipelines: Analysis Agent + Execution Agent for Ticket Handling

> **Tags**: Java, Agent, ReAct, MCP, j-langchain, Multi-Agent, Customer Support  
> **Prerequisite**: [Embedding AgentExecutor in a chain](15-agent-executor-embed.md)

---

## 1. Motivation

Some workflows require a clear hand-off between “analysis” and “execution,” each with different tools. Mixing both sets into one agent leads to confusion. Instead, use two agents connected by translators.

---

## 2. Architecture

```
Complaint → TranslateHandler (prep) → Analysis Agent (ReAct) → TranslateHandler (handoff)
          → Execution Agent (Function Calling) → TranslateHandler (final reply)
```

The first agent queries business systems and decides on an action; the second writes results to the filesystem (via MCP).

---

## 3. Analysis Tools

`OrderTools` exposes four `@AgentTool` methods: `getOrderDetail`, `getLogisticsStatus`, `getRefundPolicy`, and `checkRefundEligibility`. The first three collect data; the last encodes the refund policy logic so it’s deterministic.

---

## 4. Dual-Agent Chain

```java
AgentExecutor analysisAgent = AgentExecutor.builder(chainActor)
    .llm(ChatAliyun.builder().model("qwen3.6-plus").temperature(0f).build())
    .tools(new OrderTools())
    .maxIterations(10)
    .onThought(t -> System.out.println("[分析] " + t))
    .onObservation(obs -> System.out.println("[查询结果] " + obs))
    .build();

McpAgentExecutor executionAgent = McpAgentExecutor.builder(chainActor)
    .llm(ChatAliyun.builder().model("qwen3.6-plus").temperature(0f).build())
    .tools(mcpClient, "filesystem")
    .systemPrompt("你是一个工单处理系统...")
    .maxIterations(6)
    .onToolCall(tc -> System.out.println("[执行] " + tc))
    .onObservation(obs -> System.out.println("[执行结果] " + obs))
    .build();
```

The chain wires them together with three translators (prep, handoff, final response).

---

## 5. Execution Trace

1. **Prep** logs the complaint and generates an analysis brief.
2. **Analysis agent** performs a ReAct loop: query order → query logistics → query policy → check eligibility → final recommendation.
3. **Handoff** converts the recommendation into an instruction (“write to /tmp/ticket_result.txt and read back”).
4. **Execution agent** calls `write_file` and `read_file` via the filesystem MCP server.
5. **Post-process** produces the final customer-facing reply.

---

## 6. Importance of the Handoff Layer

Never pass raw analysis text directly to the execution agent. The translator should specify target file paths, formats, timestamps, and confirmation steps. This keeps execution deterministic and auditable.

---

## 7. Agent Types

This sample mixes `AgentExecutor` (ReAct) and `McpAgentExecutor` (Function Calling) for demonstration. In production you can use two `McpAgentExecutor` instances—tool sourcing (annotation vs. MCP config) is the only difference.

---

## 8. Applicability

| Use case | Analysis agent | Execution agent |
|----------|----------------|-----------------|
| Customer tickets | Query order/logistics/policy | Write ticket records, send notifications |
| Code review | Inspect diffs, draft fixes | Write files, commit to Git |
| Reporting | Fetch metrics | Write Excel, email results |
| Procurement | Validate stock/budget | Update ERP |
| Ops triage | Check service health | Log findings, trigger alerts |

---

## 9. Summary

Chains orchestrate the flow, agents handle reasoning, and translators adapt data between stages. This separation keeps complex workflows maintainable and encourages modular tool design.

---

> Sample: `Article16CustomerService.java` (`dualAgentChain`) – requires Aliyun `qwen3.6-plus` and Node.js for NPX.
