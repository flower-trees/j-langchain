# 三个 Agent 并行调研：用 concurrent 节点构建并发-汇聚式旅游规划助手

> **标签**：`Java` `Agent` `ReAct` `j-langchain` `多Agent` `并行` `concurrent` `旅游规划` `AgentExecutor`  
> **前置阅读**：[双 Agent 串联：用分析 Agent + 执行 Agent 构建客服工单处理流水线](16-multi-agent-executor.md)  
> **适合人群**：已掌握多 Agent 串联用法，希望让多个 Agent 并行工作以提升效率的 Java 开发者

---

## 一、串行的局限：独立任务为什么不该排队

上篇文章展示了双 Agent 串联的经典用法：分析 Agent 完成后，把结论交给执行 Agent。这个设计的前提是**两个任务之间存在数据依赖**——执行 Agent 需要分析结论才能工作。

但还有另一类场景，任务之间**没有数据依赖**，彼此完全独立：

以旅游规划为例，用户提出"京都5天4夜"的需求，系统需要从三个维度调研：

- **景点维度**：热门景点有哪些？当地有什么特色美食？
- **天气维度**：4月京都天气如何？有什么出行建议？
- **费用维度**：机票多少钱？住宿怎么选？每天花费大概多少？

这三个维度的信息互不依赖，景点调研不需要等天气调研完成，费用计算也不需要等景点调研的结果。如果让它们排队串行执行，总耗时是三者之和；而并行执行，总耗时只取决于最慢的那一个。

**串行 vs 并行的本质区别**：

| 模式 | 适用场景 | 总耗时 |
|---|---|---|
| 串行（next → next） | 任务间有数据依赖，B 需要 A 的结果 | T_A + T_B + T_C |
| 并行（concurrent） | 任务间无数据依赖，A/B/C 可以同时启动 | max(T_A, T_B, T_C) |

---

## 二、整体架构

```
用户旅游需求（京都，5天4夜，4月）
     ↓
TranslateHandler（预处理：格式化为专项调研任务）
     ↓
┌────────────────────────────────────────┐
│  concurrent 并行节点                    │
│  ┌──────────────┐                      │
│  │ 景点 Agent   │ → 景点与美食报告        │
│  │ 天气 Agent   │ → 天气与出行建议报告    │
│  │ 费用 Agent   │ → 机票住宿预算报告      │
│  └──────────────┘                      │
└────────────────────────────────────────┘
     ↓ Map<alias, ChatGeneration>
next lambda（汇总：合并三路报告，组装综合规划指令）
     ↓
AgentExecutor（综合规划 Agent：生成完整行程方案）
     ↓
TranslateHandler（后处理：格式化最终输出）
     ↓
旅行规划方案
```

三个专项 Agent 并行调研，结果通过一个合并节点汇聚，再交给综合规划 Agent 生成完整方案。这种"并发-汇聚"（Fan-out / Fan-in）是并行 Agent 最典型的拓扑结构。

---

## 三、三类工具定义

每个 Agent 配备自己领域的工具，工具集之间完全隔离，不交叉。

### 景点工具

```java
static class AttractionTools {

    @AgentTool("查询目的地热门景点")
    public String getTopAttractions(@Param("城市名称，如：京都") String city) {
        return switch (city) {
            case "京都" -> "京都热门景点：金阁寺、岚山竹林、伏见稻荷大社、清水寺、哲学之道、祇园花见小路";
            case "东京" -> "东京热门景点：浅草寺、新宿御苑、皇居、秋叶原、涩谷十字路口";
            default -> city + "热门景点：历史文化区、著名寺庙、传统市场、自然公园";
        };
    }

    @AgentTool("查询目的地特色美食")
    public String getLocalCuisine(@Param("城市名称，如：京都") String city) {
        return switch (city) {
            case "京都" -> "京都特色美食：汤豆腐、怀石料理、京野菜、抹茶甜点、鸭川河边料理、锦市场小吃";
            default -> city + "特色美食：当地传统料理、新鲜海鲜、街边小吃";
        };
    }
}
```

### 天气工具

```java
static class WeatherTools {

    @AgentTool("查询目的地指定月份天气")
    public String getWeatherByMonth(
            @Param("城市名称，如：京都") String city,
            @Param("出行月份，如：4月") String month) {
        if ("京都".equals(city)) {
            return switch (month) {
                case "3月", "4月" -> "京都" + month + "：樱花季，气温 10~20°C，晴天为主，人流量极大，建议提前3个月预订住宿";
                case "11月" -> "京都11月：红叶季，气温 8~18°C，色彩最美";
                default -> "京都" + month + "：气温适中，人流平稳，适合游览";
            };
        }
        return city + month + "：气温适中，天气较好，适合出行";
    }

    @AgentTool("查询目的地出行注意事项")
    public String getTravelAdvice(
            @Param("城市名称，如：京都") String city,
            @Param("出行月份，如：4月") String month) {
        if ("京都".equals(city) && ("3月".equals(month) || "4月".equals(month))) {
            return "京都4月出行建议：" +
                "①提前3个月预订住宿，樱花季房价是平时2~3倍；" +
                "②工作日前往热门景点人更少；" +
                "③推荐路线：岚山→金阁寺→祇园→清水寺→伏见稻荷（2天起步）；" +
                "④建议购买IC交通卡，公交比计程车便宜3倍以上";
        }
        return city + month + "出行建议：提前规划行程，注意当地文化礼仪，避开本地节假日高峰";
    }
}
```

### 费用工具

```java
static class BudgetTools {

    @AgentTool("查询国际往返机票价格")
    public String getFlightCost(
            @Param("目的地城市，如：京都") String destination,
            @Param("出行月份，如：4月") String month) {
        if ("京都".equals(destination) && ("3月".equals(month) || "4月".equals(month))) {
            return "上海→大阪关西机场（距京都最近）樱花季：经济舱往返 ¥2800~4500，需提前2个月购买；" +
                "大阪→京都：JR新干线 ¥75/人单程，约15分钟";
        }
        return "上海→" + destination + " " + month + "机票：经济舱往返约 ¥2200~3500，建议提前1个月购买";
    }

    @AgentTool("查询目的地住宿费用")
    public String getHotelCost(
            @Param("城市名称，如：京都") String city,
            @Param("住宿晚数，如：4晚") String nights) {
        return switch (city) {
            case "京都" -> "京都住宿（" + nights + "）：经济型民宿 ¥200~400/晚，中端酒店 ¥600~1200/晚，高端和式旅馆 ¥2000+/晚；" +
                "樱花季建议提前预订，旺季涨价明显";
            default -> city + "住宿（" + nights + "）：均价 ¥500~1000/晚";
        };
    }

    @AgentTool("查询目的地每日餐饮与交通费用")
    public String getDailyExpense(@Param("城市名称，如：京都") String city) {
        return switch (city) {
            case "京都" -> "京都每日开销：餐饮 ¥100~300，交通 ¥50~100（巴士一日券¥70），景点门票 ¥50~200，合计约 ¥200~600/天";
            default -> city + "每日开销：约 ¥200~500/天";
        };
    }
}
```

### 综合规划工具

综合规划 Agent 只需一个工具——获取当前时间，用于在规划方案中标注生成时间：

```java
static class PlanTools {

    @AgentTool("获取当前规划生成时间")
    public String getPlanTimestamp() {
        return "规划生成时间：" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy年MM月dd日 HH:mm"));
    }
}
```

---

## 四、构建并行流水线

```java
@Test
public void parallelTravelResearch() {

    // ── Agent1：景点 Agent ────────────────────────────────────────────────
    AgentExecutor attractionAgent = AgentExecutor.builder(chainActor)
        .llm(ChatAliyun.builder().model("qwen-plus").temperature(0f).build())
        .tools(new AttractionTools())
        .maxIterations(6)
        .onThought(t -> System.out.println("[景点Agent] " + t))
        .onObservation(obs -> System.out.println("[景点结果] " + obs))
        .build();

    // ── Agent2：天气 Agent ────────────────────────────────────────────────
    AgentExecutor weatherAgent = AgentExecutor.builder(chainActor)
        .llm(ChatAliyun.builder().model("qwen-plus").temperature(0f).build())
        .tools(new WeatherTools())
        .maxIterations(6)
        .onThought(t -> System.out.println("[天气Agent] " + t))
        .onObservation(obs -> System.out.println("[天气结果] " + obs))
        .build();

    // ── Agent3：费用 Agent ────────────────────────────────────────────────
    AgentExecutor budgetAgent = AgentExecutor.builder(chainActor)
        .llm(ChatAliyun.builder().model("qwen-plus").temperature(0f).build())
        .tools(new BudgetTools())
        .maxIterations(8)
        .onThought(t -> System.out.println("[费用Agent] " + t))
        .onObservation(obs -> System.out.println("[费用结果] " + obs))
        .build();

    // ── Agent4：综合规划 Agent ─────────────────────────────────────────────
    AgentExecutor synthesisAgent = AgentExecutor.builder(chainActor)
        .llm(ChatAliyun.builder().model("qwen-plus").temperature(0.7f).build())
        .tools(new PlanTools())
        .maxIterations(3)
        .onThought(t -> System.out.println("[综合Agent] " + t))
        .onObservation(obs -> System.out.println("[综合结果] " + obs))
        .build();

    // ── 并行流水线 ────────────────────────────────────────────────────────
    FlowInstance travelPlan = chainActor.builder()

        // 节点1：预处理——把用户输入转化为专项调研任务描述
        .next(new TranslateHandler<>(userInput -> {
            System.out.println("\n========== 收到旅游规划请求 ==========");
            System.out.println(userInput);
            System.out.println("\n--- 启动三路并行专项调研 ---");
            return "旅游需求：" + userInput +
                "\n请根据你的专业领域，针对该目的地提供详细的专项分析和建议。";
        }))

        // 节点2：并行执行——三个 Agent 同时运行，各自独立调研
        .concurrent(
            600000, // 超时 10分钟
            Info.c(attractionAgent).cAlias("attraction"),
            Info.c(weatherAgent).cAlias("weather"),
            Info.c(budgetAgent).cAlias("budget")
        )

        // 节点3：汇总——合并三路结果，组装综合规划指令
        .next(map -> {
            Map<String, Object> results = (Map<String, Object>) map;
            String attractionReport = ((ChatGeneration) results.get("attraction")).getText();
            String weatherReport    = ((ChatGeneration) results.get("weather")).getText();
            String budgetReport     = ((ChatGeneration) results.get("budget")).getText();

            System.out.println("\n--- 三路调研完成，移交综合规划 Agent ---");

            return "请先获取当前规划时间，然后根据以下三份专项调研报告，制定完整的旅行规划方案：\n\n" +
                "=== 景点与体验报告 ===\n" + attractionReport + "\n\n" +
                "=== 天气与时节报告 ===\n" + weatherReport + "\n\n" +
                "=== 费用与预算报告 ===\n" + budgetReport + "\n\n" +
                "请输出：1)每日行程安排；2)预算汇总表；3)出行注意事项。";
        })

        // 节点4：综合规划 Agent——基于三份报告生成完整旅行规划
        .next(synthesisAgent)

        // 节点5：后处理——格式化最终输出
        .next(new TranslateHandler<>(output -> {
            String plan = ((ChatGeneration) output).getText();
            return "\n========== 旅行规划方案 ==========\n" + plan +
                "\n==================================";
        }))

        .build();

    String result = chainActor.invoke(travelPlan, Map.of(
        "input", "京都，5天4夜，4月出发，预算适中"
    ));

    System.out.println(result);
}
```

---

## 五、完整执行过程

**阶段一：收到请求**
```
========== 收到旅游规划请求 ==========
京都，5天4夜，4月出发，预算适中

--- 启动三路并行专项调研 ---
```

**阶段二：三个 Agent 并行运行（输出交错出现）**
```
[景点Agent] Thought: 需要查询京都的热门景点。
Action: get_top_attractions
Action Input: {"city": "京都"}
[景点结果] 京都热门景点：金阁寺、岚山竹林、伏见稻荷大社、清水寺...

[天气Agent] Thought: 需要查询京都4月的天气情况。
Action: get_weather_by_month
Action Input: {"city": "京都", "month": "4月"}
[天气结果] 京都4月：樱花季，气温 10~20°C，晴天为主，人流量极大...

[费用Agent] Thought: 需要查询机票、住宿和每日开销。
Action: get_flight_cost
Action Input: {"destination": "京都", "month": "4月"}
[费用结果] 上海→大阪关西机场樱花季：经济舱往返 ¥2800~4500...

[景点Agent] Thought: 还需要查询当地美食。
Action: get_local_cuisine
Action Input: {"city": "京都"}
[景点结果] 京都特色美食：汤豆腐、怀石料理、京野菜、抹茶甜点...

[费用Agent] Thought: 继续查询住宿和每日开销。
Action: get_hotel_cost
Action Input: {"city": "京都", "nights": "4晚"}
[费用结果] 京都住宿（4晚）：经济型民宿 ¥200~400/晚...

[天气Agent] Thought: 还需要给出出行建议。
Action: get_travel_advice
Action Input: {"city": "京都", "month": "4月"}
[天气结果] 京都4月出行建议：①提前3个月预订住宿...

[费用Agent] Action: get_daily_expense
Action Input: {"city": "京都"}
[费用结果] 京都每日开销：餐饮 ¥100~300，交通 ¥50~100...

[景点Agent] Final Answer: 京都热门景点包括金阁寺、岚山竹林...
[天气Agent] Final Answer: 京都4月正值樱花季，气温宜人...
[费用Agent] Final Answer: 5天4夜京都行预算估算...
```

**阶段三：汇总并移交综合 Agent**
```
--- 三路调研完成，移交综合规划 Agent ---
[景点报告] 京都热门景点包括金阁寺、岚山竹林...
[天气报告] 京都4月正值樱花季，气温宜人...
[费用报告] 5天4夜京都行预算估算...

--- Agent4：开始综合规划 ---
[综合Agent] Thought: 先获取当前时间，再制定规划。
Action: get_plan_timestamp
[综合结果] 规划生成时间：2025年04月15日 14:32

[综合Agent] Final Answer: 
规划生成时间：2025年04月15日 14:32

=== 京都5天4夜樱花季旅行规划 ===

【每日行程】
第1天：抵达大阪→JR前往京都→岚山竹林→天龙寺→嵯峨野小火车
第2天：金阁寺→仁和寺→龙安寺→哲学之道→南禅寺
第3天：伏见稻荷大社（建议清晨6点前）→月桂冠大仓纪念馆→锦市场
第4天：清水寺→产宁坂→祇园花见小路→八坂神社→先斗町晚餐
第5天：上午自由活动→下午前往大阪关西机场返程

【预算汇总】
- 往返机票：¥3500（预估，提前购买）
- 住宿4晚：¥2400（中端酒店¥600/晚）
- 每日开销：¥400×5天 = ¥2000
- 合计：约 ¥7900/人

【注意事项】
1. 住宿务必提前3个月预订，樱花季一房难求
2. 购买IC卡（Suica/ICOCA），公交出行更便捷
3. 伏见稻荷大社清晨人少，避开白天高峰
4. 怀石料理建议提前预约，人均¥500+
```

**阶段四：最终输出**
```
========== 旅行规划方案 ==========
（以上完整规划内容）
==================================
```

---

## 六、并行节点的两个关键设计

### 1. `cAlias`：给并行结果命名

`concurrent` 执行完成后，返回一个 `Map<String, Object>`，key 是每个并行节点的标识。不加 `cAlias` 的话，key 是 AgentExecutor 内部自动生成的 UUID，合并节点无法通过有意义的名字来取值：

```java
// ❌ 不加 cAlias：key 是内部 UUID，无法通过名字取值
.concurrent(attractionAgent, weatherAgent, budgetAgent)
.next(map -> {
    // map 的 key 是类似 "org.salt.jlangchain.core.agent.AgentExecutor@3a1b2c3d"，无法直接使用
})

// ✅ 加 cAlias：key 是有意义的名字
.concurrent(
    Info.c(attractionAgent).cAlias("attraction"),
    Info.c(weatherAgent).cAlias("weather"),
    Info.c(budgetAgent).cAlias("budget")
)
.next(map -> {
    String report = ((ChatGeneration) map.get("attraction")).getText(); // 清晰
})
```

### 2. 合并节点的职责：三件事缺一不可

合并节点（节点3的 lambda）要承担三件事：

**提取**：从 `Map<String, Object>` 中取出各 Agent 的 `ChatGeneration`，调用 `.getText()` 拿到文本报告。

**组装**：把三份报告拼接成综合 Agent 能理解的 prompt，结构要清晰（每份报告有明确的标题分隔），避免让模型自己去猜哪段是哪个维度的信息。

**说明任务**：明确告诉综合 Agent 要输出什么（每日行程 + 预算汇总 + 注意事项），不要只是把三份报告扔给它，期望它自己决定输出格式。

```java
.next(map -> {
    // 提取
    String attractionReport = ((ChatGeneration) map.get("attraction")).getText();
    String weatherReport    = ((ChatGeneration) map.get("weather")).getText();
    String budgetReport     = ((ChatGeneration) map.get("budget")).getText();

    // 组装 + 说明任务
    return "请先获取当前规划时间，然后根据以下三份专项调研报告，制定完整的旅行规划方案：\n\n" +
        "=== 景点与体验报告 ===\n" + attractionReport + "\n\n" +
        "=== 天气与时节报告 ===\n" + weatherReport + "\n\n" +
        "=== 费用与预算报告 ===\n" + budgetReport + "\n\n" +
        "请输出：1)每日行程安排；2)预算汇总表；3)出行注意事项。";
})
```

合并节点是并行流水线中最容易被低估的部分——三份报告的拼接质量直接决定综合 Agent 的输出质量。

---

## 七、超时控制

三个 Agent 并行运行时，框架默认等待所有 Agent 完成（或超时）。默认超时为 3000ms，对于调用外部 LLM 的场景通常不够，可以在 `concurrent` 里指定更长的超时时间（单位：毫秒）：

```java
.concurrent(
    5000L,   // 最长等待 5 秒
    Info.c(attractionAgent).cAlias("attraction"),
    Info.c(weatherAgent).cAlias("weather"),
    Info.c(budgetAgent).cAlias("budget")
)
```

超时后，未完成的 Agent 结果不会进入合并 Map，合并节点收到的 Map 会缺少对应的 key，需要在合并逻辑中做防御性处理：

```java
.next(map -> {
    Map<String, Object> results = (Map<String, Object>) map;
    String attractionReport = results.containsKey("attraction")
        ? ((ChatGeneration) results.get("attraction")).getText()
        : "（景点信息获取超时，请稍后重试）";
    // ... 其余同理
})
```

---

## 八、串行 vs 并行：怎么选

| 维度 | 串行（next → next） | 并行（concurrent） |
|---|---|---|
| 适用条件 | 下一步需要上一步的结果 | 各步骤输入来自同一上游，互不依赖 |
| 典型例子 | 分析完才能执行，审批完才能通知 | 多维度信息采集、多路评分、多模型投票 |
| 耗时 | T_A + T_B + T_C | max(T_A, T_B, T_C) |
| 工具设计 | 每个 Agent 的工具可以有关联 | 各 Agent 工具集完全独立 |
| 合并节点 | TranslateHandler 做格式转换 | 合并 Map，组装综合 prompt |

本文的三 Agent 并行 + 一 Agent 综合，是"并发-汇聚"的基础形态。如果需要更复杂的拓扑（比如先并行采集，再串行决策，再并行通知），只需在 `chainActor.builder()` 里组合 `.concurrent()`、`.next()`、`.notify()` 等节点，每种节点各司其职。

---

## 九、总结

本篇展示了 j-langchain 中并行 Agent 的完整用法：

- `concurrent()` 节点让多个 `AgentExecutor` 同时运行，底层用 `CountDownLatch` 等待所有结果
- `Info.c(agent).cAlias("key")` 给每个并行 Agent 的结果命名，合并节点通过 key 精确取值
- 合并 lambda（`.next(map -> {...})`）承担提取、组装、说明任务三件事
- 超时时间可以在 `concurrent(timeout, ...)` 里指定，避免单个 Agent 阻塞整个流程

与串行多 Agent 一起，这两种模式覆盖了绝大多数多 Agent 协作场景：**串行解决前后依赖，并行解决独立采集**。

---

> 📎 相关资源
> - 完整示例：[Article18ParallelTravelResearch.java](../../../src/test/java/org/salt/jlangchain/demo/article/Article18ParallelTravelResearch.java)，对应方法 `parallelTravelResearch()`
> - j-langchain GitHub：https://github.com/flower-trees/j-langchain
> - j-langchain Gitee 镜像：https://gitee.com/flower-trees-z/j-langchain
> - 运行环境：需配置阿里云 API Key（`ALIYUN_KEY`），示例模型 `qwen-plus`
