# Embedding AgentExecutor in a Chain: Making the Agent a Node in Your Pipeline

> **Tags**: `Java` `ReAct` `Agent` `j-langchain` `Chain` `AgentExecutor` `travel planning`  
> **Prerequisite**: [AgentExecutor: Launch a Java ReAct Agent Without Handwriting the Loop](09-agent-executor.md) → [Multi-step ReAct: Let the Agent Autonomously Compare Airline Prices and Book](10-flight-compare-agent.md)  
> **Audience**: Java developers who already use `AgentExecutor` and want to combine Agents with other processing logic into a complete business workflow

---

## 1. The Agent Is Not the End of the Pipeline

The previous articles used `AgentExecutor` as a standalone execution unit: accept user input, autonomously call tools, return the final answer. This works for simple scenarios, but real business is often more complex —

The user's raw input may need **pre-processing** to become an instruction the Agent can understand; the Agent's output may need **post-processing** to be formatted as a report, written to a database, or used to trigger a downstream flow.

j-langchain's `AgentExecutor` implements the `BaseRunnable` interface, which means it can be placed directly inside `chainActor.builder().next(...)` **just like a regular `TranslateHandler`, `PromptTemplate`, or `LLM` node**, becoming a node in the pipeline.

This article uses a travel planning assistant as an example to show the complete usage of this pattern.

---

## 2. Scenario: Three Steps to a Travel Plan

The user inputs a list of city names, and the system should:

1. **Pre-process**: expand the city names into a structured task description that tells the Agent what information to gather
2. **Agent execution**: call weather, flight, and hotel tools, completing multi-city data collection
3. **Post-process**: wrap the Agent's raw answer into a formatted travel planning report

These three steps form a linear pipeline, with `AgentExecutor` in the middle:

```
User input (city list)
      ↓
TranslateHandler (pre-process: expand into complete task instruction)
      ↓
AgentExecutor (multi-round tool calls: weather + flights + hotels)
      ↓
TranslateHandler (post-process: format as travel report)
      ↓
Final output
```

---

## 3. Tool Definitions

Three types of tools cover the core information needed for travel decisions. Each tool returns data for one city; the model will automatically loop over multiple cities:

```java
static class TravelTools {

    @AgentTool("查询城市天气")
    public String getWeather(String city) {
        return switch (city) {
            case "成都" -> "成都天气：多云，18~26°C，下午有小雨";
            case "西安" -> "西安天气：晴，16~28°C";
            case "桂林" -> "桂林天气：阵雨，23~30°C，晚间有雷阵雨";
            case "三亚" -> "三亚天气：晴，28~33°C，紫外线很强";
            default    -> city + "天气：晴到多云，18~30°C";
        };
    }

    @AgentTool("查询机票价格")
    public String getFlightPrice(String city) {
        return switch (city) {
            case "成都" -> "上海 → 成都：¥980，上海 → 成都（商务舱）¥2650";
            case "西安" -> "上海 → 西安：¥850，含10公斤行李";
            case "桂林" -> "上海 → 桂林：¥1180，含20公斤行李";
            case "三亚" -> "上海 → 三亚：¥1600，含15公斤行李";
            default    -> "上海 → " + city + "：¥1200（经济舱）";
        };
    }

    @AgentTool("查询酒店均价")
    public String getHotelPrice(String city) {
        return switch (city) {
            case "成都" -> "成都：三星均价 ¥280/晚，四星均价 ¥520/晚，推荐春熙路附近";
            case "西安" -> "西安：三星均价 ¥220/晚，四星均价 ¥450/晚，推荐钟楼附近";
            case "桂林" -> "桂林：三星均价 ¥200/晚，四星均价 ¥380/晚，推荐两江四湖景区";
            case "三亚" -> "三亚：三星均价 ¥480/晚，四星均价 ¥950/晚，推荐亚龙湾";
            default    -> city + "：均价 ¥300/晚";
        };
    }
}
```

In production, simply replace the method bodies with real API calls — the Agent-layer code needs no changes at all.

---

## 4. Full Code

```java
@Test
public void planTrip() {

    // Step 1: build AgentExecutor for multi-city data collection
    AgentExecutor travelAgent = AgentExecutor.builder(chainActor)
        .llm(ChatAliyun.builder().model("qwen-plus").temperature(0f).build())
        .tools(new TravelTools())
        .maxIterations(15)    // 3 cities × 3 tool types = 9 calls; leave headroom
        .onThought(System.out::print)
        .onObservation(obs -> System.out.println("\nObservation: " + obs))
        .build();

    // Step 2: build the pipeline, with AgentExecutor as the middle node
    FlowInstance travelPlanChain = chainActor.builder()

        // Node 1: pre-process — expand the city list into a structured task instruction
        .next(new TranslateHandler<>(userInput -> {
            System.out.println("=== 解析旅行需求 ===");
            System.out.println("用户需求：" + userInput);
            return "我从上海出发，计划去以下城市旅游：" + userInput +
                "。请帮我：1)查询每个城市的天气；2)查询上海到各城市的机票价格；" +
                "3)查询各城市酒店均价；4)综合以上信息，推荐最佳出行顺序和预算估算。";
        }))

        // Node 2: AgentExecutor — multi-round tool calls, collect data for all cities
        .next(travelAgent)

        // Node 3: post-process — wrap the Agent's raw answer into a travel report
        .next(new TranslateHandler<>(output -> {
            System.out.println("\n=== 生成旅行报告 ===");
            String agentAnswer = ((ChatGeneration) output).getText();
            return "\n========== 旅行规划报告 ==========\n"
                + agentAnswer
                + "\n==================================\n"
                + "以上建议由 AI 旅行助手自动生成，请结合实际情况参考。";
        }))

        .build();

    // Step 3: invoke, passing only the city list
    String report = chainActor.invoke(travelPlanChain, Map.of("input", "成都、西安、桂林"));
    System.out.println(report);
}
```

---

## 5. Execution Process

Running the code shows the three phases connecting cleanly:

**Phase 1: Pre-processing**
```
=== 解析旅行需求 ===
用户需求：成都、西安、桂林
```

**Phase 2: Agent multi-round reasoning (excerpt)**
```
Thought: 需要依次查询三个城市的天气、机票和酒店价格。先查成都天气。
Action: get_weather
Action Input: 成都
Observation: 成都天气：多云，18~26°C，下午有小雨

Thought: 再查成都机票价格。
Action: get_flight_price
Action Input: 成都
Observation: 上海 → 成都：¥980，上海 → 成都（商务舱）¥2650

... (9 tool calls total: 3 cities × weather/flight/hotel)

Thought: 已收集所有数据，进行综合分析。
Final Answer: 根据收集的信息，推荐出行顺序为...
```

**Phase 3: Post-processing output**
```
=== 生成旅行报告 ===

========== 旅行规划报告 ==========
根据查询结果，综合天气、费用和体验：

推荐出行顺序：西安 → 成都 → 桂林
- 西安：天气最佳（晴，16~28°C），机票最便宜（¥850），适合首站
- 成都：天气尚可，机票性价比高（¥980），美食丰富
- 桂林：虽有阵雨，但景色最美，建议最后游览避开疲劳

预算参考（7天行程，三星酒店）：
- 机票：¥850 + ¥980 + ¥1180 = ¥3010
- 酒店（7晚）：均价约 ¥230/晚 × 7 ≈ ¥1610
- 合计参考：约 ¥4620 起（不含餐饮景点）
==================================
以上建议由 AI 旅行助手自动生成，请结合实际情况参考。
```

---

## 6. What Problem This Pattern Solves

Embedding `AgentExecutor` in a Chain's core value is making **the Agent's responsibility more focused**.

Without this pattern, there are typically two alternatives:

**Option 1: Cram pre- and post-processing logic into the System Prompt**

```java
agent.invoke("用户输入成都西安桂林三个城市，先查天气机票酒店，然后用报告格式输出，加上免责声明...");
```

The problem: the System Prompt grows increasingly bloated, format requirements become increasingly complex, and a slight model deviation produces output that doesn't match expectations.

**Option 2: Do pre- and post-processing manually in the caller**

```java
String formattedInput = preProcess(userInput);
ChatGeneration agentResult = agent.invoke(formattedInput);
String finalReport = postProcess(agentResult);
```

This works, but every caller has to repeat this glue code, and the formatting logic is scattered everywhere and hard to maintain.

**The embedded-in-Chain approach**: pre-processing, Agent execution, and post-processing are each independent nodes with clear responsibilities, each replaceable independently without affecting the others.

---

## 7. How to Think About `maxIterations`

This example sets `maxIterations(15)`, because the task volume can be precisely estimated:

```
City count (3) × tool types (3: weather, flight, hotel) = 9 Actions
Plus a Thought for each Action ≈ 9 rounds
Plus the final summary Thought + Final Answer ≈ 2 rounds
Actual consumption ~11~12 rounds; set 15 for headroom
```

If the city count is dynamic (the user may enter 2 or 5 cities), you can dynamically calculate it in the pre-processing node:

```java
// Dynamic maxIterations calculation in pre-processing node (illustration)
int cityCount = userInput.split("、").length;
int maxIter = cityCount * 3 + 3;  // 3 tools per city + buffer
```

However, `AgentExecutor` currently doesn't support dynamically changing `maxIterations`. The simpler approach is to set a fixed value large enough based on the business upper bound.

---

## 8. Summary

Embedding `AgentExecutor` in a Chain is the right pattern when: **the user's input needs structured processing, or the Agent's output needs further transformation** before being returned to the user.

This pattern's three-layer structure — **pre-process → Agent → post-process** — moves the data formatting responsibility out of the System Prompt and into clear code nodes. The Agent only needs to focus on tool calls and reasoning, with no concern for input format or output style.

As business complexity grows, each layer can expand independently: the pre-processing node can incorporate intent recognition and slot filling; the post-processing node can write to databases and trigger notifications; the Agent node can be replaced with a differently configured `AgentExecutor` or `McpAgentExecutor` — the overall structure doesn't need to change.

---

> 📎 Resources
> - Full example: [Article15TravelAgent.java](../../../src/test/java/org/salt/jlangchain/demo/article/Article15TravelAgent.java), method `planTrip()`
> - j-langchain GitHub: https://github.com/flower-trees/j-langchain
> - j-langchain Gitee mirror: https://gitee.com/flower-trees-z/j-langchain
> - Runtime requirements: Aliyun API Key required, example model `qwen-plus`
