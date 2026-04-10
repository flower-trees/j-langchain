# AgentExecutor 嵌入 Chain：让 Agent 成为流水线中的一个节点

> **标签**：`Java` `ReAct` `Agent` `j-langchain` `Chain` `AgentExecutor` `旅行规划`  
> **前置阅读**：[AgentExecutor：告别手写 ReAct 循环](09-agent-executor.md) → [多步骤 ReAct 实战：让 Agent 自主完成航司比价与订票](10-flight-compare-agent.md)  
> **适合人群**：已会用 `AgentExecutor`，希望把 Agent 和其他处理逻辑组合成完整业务流程的 Java 开发者

---

## 一、Agent 不是流程的终点

前几篇文章把 `AgentExecutor` 作为独立的执行单元来使用：接受用户输入，自主完成工具调用，返回最终答案。这适合简单场景，但真实业务往往更复杂——

用户的原始输入可能需要**预处理**才能变成 Agent 能理解的指令；Agent 的输出可能需要**后处理**，格式化成报告、写入数据库、或触发下游流程。

j-langchain 的 `AgentExecutor` 实现了 `BaseRunnable` 接口，这意味着它可以像普通的 `TranslateHandler`、`PromptTemplate`、`LLM` 节点一样，**直接放进 `chainActor.builder().next(...)` 里**，成为流水线中的一个节点。

本篇以旅行规划助手为例，展示这种模式的完整用法。

---

## 二、场景：三步完成旅行规划

用户输入一串城市名，系统要：

1. **预处理**：把城市名扩展成结构化的任务描述，告诉 Agent 需要查哪些信息
2. **Agent 执行**：调用天气、机票、酒店三类工具，完成多城市的数据收集
3. **后处理**：把 Agent 返回的原始答案包装成格式化的旅行规划报告

这三步形成一条线性流水线，中间那步是 `AgentExecutor`：

```
用户输入（城市列表）
      ↓
TranslateHandler（预处理：扩展为完整任务指令）
      ↓
AgentExecutor（多轮工具调用：查天气 + 查机票 + 查酒店）
      ↓
TranslateHandler（后处理：格式化为旅行报告）
      ↓
最终输出
```

---

## 三、工具定义

三类工具覆盖旅行决策所需的核心信息，每个工具只返回一个城市的数据，模型会自动对多个城市循环调用：

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

生产环境中这些方法体换成真实 API 调用即可，Agent 层的代码完全不需要改动。

---

## 四、完整代码

```java
@Test
public void planTrip() {

    // 第一步：构建 AgentExecutor，用于多城市数据收集
    AgentExecutor travelAgent = AgentExecutor.builder(chainActor)
        .llm(ChatAliyun.builder().model("qwen-plus").temperature(0f).build())
        .tools(new TravelTools())
        .maxIterations(15)    // 3个城市 × 3类工具 = 9次调用，留足余量
        .onThought(System.out::print)
        .onObservation(obs -> System.out.println("\nObservation: " + obs))
        .build();

    // 第二步：构建流水线，AgentExecutor 作为中间节点
    FlowInstance travelPlanChain = chainActor.builder()

        // 节点1：预处理 —— 把城市列表扩展成结构化任务指令
        .next(new TranslateHandler<>(userInput -> {
            System.out.println("=== 解析旅行需求 ===");
            System.out.println("用户需求：" + userInput);
            return "我从上海出发，计划去以下城市旅游：" + userInput +
                "。请帮我：1)查询每个城市的天气；2)查询上海到各城市的机票价格；" +
                "3)查询各城市酒店均价；4)综合以上信息，推荐最佳出行顺序和预算估算。";
        }))

        // 节点2：AgentExecutor —— 多轮工具调用，收集所有城市的数据
        .next(travelAgent)

        // 节点3：后处理 —— 把 Agent 的原始答案包装成旅行报告
        .next(new TranslateHandler<>(output -> {
            System.out.println("\n=== 生成旅行报告 ===");
            String agentAnswer = ((ChatGeneration) output).getText();
            return "\n========== 旅行规划报告 ==========\n"
                + agentAnswer
                + "\n==================================\n"
                + "以上建议由 AI 旅行助手自动生成，请结合实际情况参考。";
        }))

        .build();

    // 第三步：执行，只需传入城市列表
    String report = chainActor.invoke(travelPlanChain, Map.of("input", "成都、西安、桂林"));
    System.out.println(report);
}
```

---

## 五、执行过程

运行后可以清晰看到三个阶段的衔接：

**阶段一：预处理**
```
=== 解析旅行需求 ===
用户需求：成都、西安、桂林
```

**阶段二：Agent 多轮推理（节选）**
```
Thought: 需要依次查询三个城市的天气、机票和酒店价格。先查成都天气。
Action: get_weather
Action Input: 成都
Observation: 成都天气：多云，18~26°C，下午有小雨

Thought: 再查成都机票价格。
Action: get_flight_price
Action Input: 成都
Observation: 上海 → 成都：¥980，上海 → 成都（商务舱）¥2650

... （共 9 轮工具调用：3城市 × 天气/机票/酒店）

Thought: 已收集所有数据，进行综合分析。
Final Answer: 根据收集的信息，推荐出行顺序为...
```

**阶段三：后处理输出**
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

## 六、这种模式解决了什么问题

把 `AgentExecutor` 嵌入 Chain 的核心价值，是让 **Agent 的职责更单一**。

如果不用这种模式，通常有两种写法：

**写法一：把预处理和后处理逻辑塞进 System Prompt**

```java
agent.invoke("用户输入成都西安桂林三个城市，先查天气机票酒店，然后用报告格式输出，加上免责声明...");
```

这样做的问题是 System Prompt 越来越臃肿，格式要求越来越复杂，模型稍有偏差就会输出不符合预期的格式。

**写法二：在调用方手动做前后处理**

```java
String formattedInput = preProcess(userInput);
ChatGeneration agentResult = agent.invoke(formattedInput);
String finalReport = postProcess(agentResult);
```

这样可以，但每个调用方都要重复写这段胶水代码，而且格式化逻辑散落在各处，不易维护。

**嵌入 Chain 的写法**：预处理、Agent 执行、后处理各自是独立节点，职责清晰，可以单独替换其中任意一个，不影响其他节点。

---

## 七、maxIterations 的设置思路

本例设置了 `maxIterations(15)`，原因是任务量可以精确估算：

```
城市数量（3）× 工具种类（3：天气、机票、酒店）= 9 次 Action
加上每次 Action 对应的 Thought ≈ 9 轮
再加最后的汇总 Thought + Final Answer ≈ 2 轮
实际消耗约 11~12 轮，设 15 留有余量
```

如果城市数量是动态的（用户可能输入 2 个城市也可能输入 5 个），可以在预处理节点里动态计算并传入：

```java
// 预处理节点中动态设置迭代次数（示意）
int cityCount = userInput.split("、").length;
int maxIter = cityCount * 3 + 3;  // 每城市3个工具 + buffer
```

不过 `AgentExecutor` 目前不支持动态修改 `maxIterations`，更简单的做法是根据业务上限直接设一个足够大的固定值。

---

## 八、总结

`AgentExecutor` 嵌入 Chain 的模式适合这类场景：**用户输入需要结构化处理，或者 Agent 的输出需要进一步加工**，而不是直接返回给用户。

这种模式的三层结构——**预处理 → Agent → 后处理**——把数据格式化的责任从 System Prompt 中解放出来，交给明确的代码节点处理。Agent 只需专注于工具调用和推理，不需要关心输入格式和输出样式。

随着业务复杂度增加，每一层都可以独立扩展：预处理节点可以接入意图识别、槽位提取；后处理节点可以写入数据库、触发通知；Agent 节点可以替换成不同配置的 `AgentExecutor` 或 `McpAgentExecutor`，整体结构不需要变动。

---

> 📎 相关资源
> - 完整示例：[Article15TravelAgent.java](../../../src/test/java/org/salt/jlangchain/demo/article/Article15TravelAgent.java)，对应方法 `planTrip()`
> - j-langchain GitHub：https://github.com/flower-trees/j-langchain
> - j-langchain Gitee 镜像：https://gitee.com/flower-trees-z/j-langchain
> - 运行环境：需配置阿里云 API Key，示例模型 `qwen-plus`