# 多步骤 ReAct：航司比价订票实战

> **前置文章**：[Java 实现 ReAct Agent：工具调用与推理循环](04-react-agent.md) → [AgentExecutor：用一行代码启动 ReAct Agent](09-agent-executor.md)  
> **适合人群**：已会用 `AgentExecutor` 与 `@AgentTool`，希望串联多个工具完成业务流程的 Java 开发者  
> **核心概念**：多轮工具调用、同类工具组合、决策后再调用下游工具  
> **配套代码**：`Article10FlightAgent#flightCompareAndBook()`

---

## 场景

用户一句话提出完整目标：**查三家航司票价 → 比较 → 选最低价 → 订票**。  
中间没有人工逐步确认，完全依赖 Agent 在多轮 `Thought / Action / Observation` 中自主推进。

与文章 9 中「单次 `book_flight`」不同，这里需要**多次查询类工具**（MU / CA / CZ）各返回一条 Observation，模型汇总后再调用**订票工具**。

---

## 工具类：`FlightTools`（内置于 Article10FlightAgent）

工具定义与用例集中在 `Article10FlightAgent` 中，`FlightTools` 作为静态内部类，便于阅读和复用。

每个查询方法对应一条航线表（演示数据为内存 `Map`，生产环境可替换为真实 API）：

```java
static class FlightTools {

    @AgentTool("查询东方航空（MU）的机票价格")
    public String queryMuFlight(
            @Param("出发城市") String fromCity,
            @Param("目的城市") String toCity,
            @Param("日期，格式 YYYY-MM-DD") String date) {
        // 演示：按 route 查 MU_PRICES；无航线则返回提示文案
        // ...
    }

    @AgentTool("查询中国国际航空（CA）的机票价格")
    public String queryCaFlight(/* 同上 */) { /* ... */ }

    @AgentTool("查询南方航空（CZ）的机票价格")
    public String queryCzFlight(/* 同上 */) { /* ... */ }

    @AgentTool("确认订购机票，输入航司、出发城市、目的城市和日期")
    public String bookFlight(
            @Param("航司名称，如：东方航空、国航、南航") String airline,
            @Param("出发城市") String fromCity,
            @Param("目的城市") String toCity,
            @Param("日期，格式 YYYY-MM-DD") String date) {
        // ...
    }
}
```

---

## 构建并执行

与文章 9 相同，使用 `AgentExecutor.builder`，将工具实例换成新的 `FlightTools`：

```java
AgentExecutor agent = AgentExecutor.builder(chainActor)
    .llm(ChatAliyun.builder().model("qwen-plus").temperature(0f).build())
    .tools(new FlightTools())
    .maxIterations(10)
    .onThought(System.out::print)
    .onObservation(obs -> System.out.println("Observation: " + obs))
    .build();

ChatGeneration result = agent.invoke(
    "我要订2024-03-15从上海飞北京的机票，请帮我查询东方航空、国航、南航三家的价格，选最便宜的那家帮我订票"
);
System.out.println(result.getText());
```

JUnit 用例：`Article10FlightAgent#flightCompareAndBook()`。

---

## 典型推理过程（示意）

Agent 自主完成多步：三次询价 → 比价 → 一次 `book_flight`。下面为与演示数据一致的示意轨迹（实际 Thought 文案以模型为准）：

```
Thought: 需要分别查询三家航司的票价，先查东方航空。
Action: query_mu_flight
Action Input: {"fromCity": "上海", "toCity": "北京", "date": "2024-03-15"}

Observation: 东方航空（MU）2024-03-15 上海 → 北京，票价 ¥980

Thought: 再查国航。
Action: query_ca_flight
Action Input: {"fromCity": "上海", "toCity": "北京", "date": "2024-03-15"}

Observation: 中国国航（CA）2024-03-15 上海 → 北京，票价 ¥1150

Thought: 再查南航。
Action: query_cz_flight
Action Input: {"fromCity": "上海", "toCity": "北京", "date": "2024-03-15"}

Observation: 南方航空（CZ）2024-03-15 上海 → 北京，票价 ¥860

Thought: 三家对比：南航最便宜，调用订票。
Action: book_flight
Action Input: {"airline": "南航", "fromCity": "上海", "toCity": "北京", "date": "2024-03-15"}

Observation: ✅ 订票成功！南航 上海 → 北京，日期 2024-03-15，订单号 ORD-……

Final Answer: （模型总结比价结果与订单信息）
```

---

## 小结

| 要点 | 说明 |
|------|------|
| 工具拆分 | 每个航司一条 `@AgentTool` 方法，Observation 结构清晰，便于模型逐步消费 |
| 参数形式 | 多参数统一用 JSON `Action Input`，与文章 9 一致 |
| `maxIterations` | 多轮调用需留足上限（示例为 10） |
| 代码位置 | 工具 & 用例：`Article10FlightAgent#flightCompareAndBook()`（内部类 `FlightTools`） |

---

> **运行环境**：与文章 9 相同，需配置 `ALIYUN_KEY`（示例模型 `qwen-plus`）。
