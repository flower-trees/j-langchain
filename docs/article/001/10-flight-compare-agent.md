# 多步骤 ReAct 实战：让 Agent 自主完成航司比价与订票

> **标签**：`Java` `ReAct` `Agent` `j-langchain` `多步骤推理` `工具调用` `AgentExecutor`  
> **前置阅读**：[Java 实现 ReAct Agent：工具调用与推理循环](04-react-agent.md) → [AgentExecutor：告别手写 ReAct 循环](09-agent-executor.md)  
> **适合人群**：已会用 `AgentExecutor` 和 `@AgentTool`，希望串联多个工具完成完整业务流程的 Java 开发者

---

## 一、从单次调用到多步推理

前两篇文章演示的都是"一问一答"式的工具调用——用户问天气，Agent 调一次 `get_weather` 就结束了。但真实业务往往不是这样的。

考虑这样一个场景：

> 用户说："帮我查一下东方航空、国航、南航 2024-03-15 上海飞北京的票价，选最便宜的帮我订票。"

这句话里藏着四个动作：查 MU 票价、查 CA 票价、查 CZ 票价、比价后订票。用户不会逐步确认，他只提了一个最终目标，中间的推理和决策全部由 Agent 自主完成。

这就是**多步骤 ReAct** 的典型场景：多轮工具调用逐步积累信息，模型根据已有 Observation 决定下一步动作，直到目标达成。

---

## 二、工具设计：一个任务，四个工具

工具拆分的原则是**每个工具只做一件事，返回结构清晰**，便于模型在后续 Thought 中消费多条 Observation。

```java
static class FlightTools {

    // 演示数据：内存 Map 模拟航班数据库，生产环境替换为真实 API
    private static final Map<String, Integer> MU_PRICES = Map.of(
        "上海-北京", 980, "上海-广州", 1200, "上海-成都", 1450
    );
    private static final Map<String, Integer> CA_PRICES = Map.of(
        "上海-北京", 1150, "上海-广州", 1080, "上海-成都", 1380
    );
    private static final Map<String, Integer> CZ_PRICES = Map.of(
        "上海-北京", 860, "上海-广州", 1320, "上海-成都", 1560
    );

    // 三个查询工具，结构完全一致，航司不同
    @AgentTool("查询东方航空（MU）的机票价格")
    public String queryMuFlight(
            @Param("出发城市") String fromCity,
            @Param("目的城市") String toCity,
            @Param("日期，格式 YYYY-MM-DD") String date) {
        String route = fromCity + "-" + toCity;
        Integer price = MU_PRICES.get(route);
        if (price == null) {
            return String.format("东方航空暂无 %s 航线", route);
        }
        return String.format("东方航空（MU）%s %s → %s，票价 ¥%d", date, fromCity, toCity, price);
    }

    @AgentTool("查询中国国际航空（CA）的机票价格")
    public String queryCaFlight(
            @Param("出发城市") String fromCity,
            @Param("目的城市") String toCity,
            @Param("日期，格式 YYYY-MM-DD") String date) {
        String route = fromCity + "-" + toCity;
        Integer price = CA_PRICES.get(route);
        if (price == null) {
            return String.format("国航暂无 %s 航线", route);
        }
        return String.format("中国国航（CA）%s %s → %s，票价 ¥%d", date, fromCity, toCity, price);
    }

    @AgentTool("查询南方航空（CZ）的机票价格")
    public String queryCzFlight(
            @Param("出发城市") String fromCity,
            @Param("目的城市") String toCity,
            @Param("日期，格式 YYYY-MM-DD") String date) {
        String route = fromCity + "-" + toCity;
        Integer price = CZ_PRICES.get(route);
        if (price == null) {
            return String.format("南航暂无 %s 航线", route);
        }
        return String.format("南方航空（CZ）%s %s → %s，票价 ¥%d", date, fromCity, toCity, price);
    }

    // 订票工具：在比价完成后由模型决策调用
    @AgentTool("确认订购机票，输入航司、出发城市、目的城市和日期")
    public String bookFlight(
            @Param("航司名称，如：东方航空、国航、南航") String airline,
            @Param("出发城市") String fromCity,
            @Param("目的城市") String toCity,
            @Param("日期，格式 YYYY-MM-DD") String date) {
        return String.format("✅ 订票成功！%s %s → %s，日期 %s，订单号 ORD-%d",
            airline, fromCity, toCity, date, (long)(Math.random() * 900000) + 100000);
    }
}
```

这里有个设计取舍值得注意：三家航司的查询逻辑完全相同，也可以合并成一个带 `airline` 参数的工具。但拆成三个独立工具有一个好处——**模型不需要在参数层面决定调哪家**，减少一层推理负担，在复杂场景下出错率更低。

---

## 三、构建 Agent，只换工具类

`AgentExecutor` 的构建方式与上篇完全一致，唯一的变化是把工具换成 `FlightTools`：

```java
@Test
public void flightCompareAndBook() {

    AgentExecutor agent = AgentExecutor.builder(chainActor)
        .llm(ChatAliyun.builder().model("qwen-plus").temperature(0f).build())
        .tools(new FlightTools())   // 传入工具类，框架自动扫描 @AgentTool 方法
        .maxIterations(10)          // 多步推理需要留足轮次：3次查询 + 1次订票 + 若干 Thought
        .onThought(System.out::print)
        .onObservation(obs -> System.out.println("Observation: " + obs))
        .build();

    ChatGeneration result = agent.invoke(
        "我要订2024-03-15从上海飞北京的机票，请帮我查询东方航空、国航、南航三家的价格，选最便宜的那家帮我订票"
    );

    System.out.println("\n=== 最终答案 ===");
    System.out.println(result.getText());
}
```

`maxIterations` 设为 10 留有余量：三次查询工具 + 一次订票工具 = 至少 4 轮 Action，加上每轮的 Thought，实际消耗大约 6~8 轮。

---

## 四、Agent 的完整推理轨迹

运行后，`onThought` 和 `onObservation` 回调会把整个推理过程打印出来：

```
Thought: 需要分别查询三家航司的票价。先查东方航空。
Action: query_mu_flight
Action Input: {"fromCity": "上海", "toCity": "北京", "date": "2024-03-15"}

Observation: 东方航空（MU）2024-03-15 上海 → 北京，票价 ¥980

Thought: 东方航空 ¥980，继续查国航。
Action: query_ca_flight
Action Input: {"fromCity": "上海", "toCity": "北京", "date": "2024-03-15"}

Observation: 中国国航（CA）2024-03-15 上海 → 北京，票价 ¥1150

Thought: 国航 ¥1150，继续查南航。
Action: query_cz_flight
Action Input: {"fromCity": "上海", "toCity": "北京", "date": "2024-03-15"}

Observation: 南方航空（CZ）2024-03-15 上海 → 北京，票价 ¥860

Thought: 三家对比：南航 ¥860 最便宜，调用订票工具。
Action: book_flight
Action Input: {"airline": "南航", "fromCity": "上海", "toCity": "北京", "date": "2024-03-15"}

Observation: ✅ 订票成功！南航 上海 → 北京，日期 2024-03-15，订单号 ORD-342871

Final Answer: 已为您查询三家航司票价：东方航空 ¥980、国航 ¥1150、南航 ¥860。
             南航价格最低，已成功订票，订单号 ORD-342871。
```

整个过程中，模型做了三件事：**按顺序收集信息**（三次查询）、**在 Thought 中做比较判断**、**选定最优方案后调用下游工具**。这正是 ReAct 框架的核心价值——推理与行动交替进行，每一步的 Observation 都成为下一步 Thought 的输入。

---

## 五、关键设计点总结

**工具拆分粒度**：每个工具只做一件事，Observation 格式固定统一。工具粒度越细，模型在 Thought 中的推理越容易，出错率越低。

**参数设计**：多参数工具统一用 JSON 格式的 Action Input，`@Param` 注解的描述要具体，尤其是日期格式这类有约束的参数，明确写在注解里能显著减少模型的格式错误。

**maxIterations 留有余量**：估算最坏情况下的工具调用次数，再留 2~3 轮 buffer，避免因轮次不足导致推理中断。本例最少需要 4 次 Action，设 10 是合理的上限。

**推理过程可观测**：`onThought` 和 `onObservation` 两个回调不只是调试用途，在生产环境中可以接入日志系统，完整记录每次工具调用的输入输出，方便排查问题。

---

## 六、扩展思路

这个模式可以直接复用到更多业务场景，只需替换工具类：

| 场景 | 工具组合 | 最终动作 |
|---|---|---|
| 比价采购 | 多供应商报价查询 × N | 下单 |
| 智能推荐 | 多维度数据查询（评分/价格/库存） | 生成推荐报告 |
| 数据汇总 | 多表/多系统查询 | 写入汇总表 |
| 审批流程 | 资质查询 + 规则校验 | 提交审批 / 拒绝并说明原因 |

核心模式是一样的：**多个信息收集工具 + 一个执行工具，由 Agent 自主决策何时从"收集"切换到"执行"**。

---

> 📎 相关资源
> - 完整示例：[Article10FlightAgent.java](../../../src/test/java/org/salt/jlangchain/demo/article/Article10FlightAgent.java)，对应方法 `flightCompareAndBook()`
> - j-langchain GitHub：https://github.com/flower-trees/j-langchain
> - j-langchain Gitee 镜像：https://gitee.com/flower-trees-z/j-langchain
> - 运行环境：需配置阿里云 API Key，示例模型 `qwen-plus`