/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.salt.jlangchain.demo.article;

import org.apache.commons.lang3.StringUtils;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.salt.function.flow.FlowInstance;
import org.salt.function.flow.Info;
import org.salt.function.flow.context.ContextBus;
import org.salt.jlangchain.TestApplication;
import org.salt.jlangchain.core.ChainActor;
import org.salt.jlangchain.core.agent.AgentExecutor;
import org.salt.jlangchain.core.handler.ConsumerHandler;
import org.salt.jlangchain.core.handler.TranslateHandler;
import org.salt.jlangchain.core.llm.aliyun.ChatAliyun;
import org.salt.jlangchain.core.llm.ollama.ChatOllama;
import org.salt.jlangchain.core.message.AIMessage;
import org.salt.jlangchain.core.parser.StrOutputParser;
import org.salt.jlangchain.core.parser.generation.ChatGeneration;
import org.salt.jlangchain.core.prompt.string.PromptTemplate;
import org.salt.jlangchain.core.prompt.value.StringPromptValue;
import org.salt.jlangchain.rag.tools.Tool;
import org.salt.jlangchain.rag.tools.annotation.AgentTool;
import org.salt.jlangchain.rag.tools.annotation.Param;
import org.salt.jlangchain.utils.PromptUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.junit4.SpringRunner;

import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * 文章4：Java 实现 ReAct Agent：工具调用与推理循环
 *
 * 演示内容：
 * 1. simpleTool      - 定义和使用单个工具
 * 2. multiToolAgent  - 多工具 ReAct Agent 完整实现
 *    - Thought（思考）→ Action（调用工具）→ Observation（观察结果）→ Final Answer
 */
@RunWith(SpringRunner.class)
@SpringBootTest(classes = TestApplication.class)
@SpringBootConfiguration
public class Article04ReactAgent {

    @Autowired
    ChainActor chainActor;

    /**
     * 演示：定义工具
     * 工具是 Agent 的"手和脚"，让 LLM 能够调用外部系统
     */
    @Test
    public void defineTool() {
        // 工具1：查询天气
        Tool weatherTool = Tool.builder()
            .name("get_weather")
            .params("location: String")
            .description("获取指定城市的天气信息，输入城市名称")
            .func(location -> String.format("%s 今天天气晴朗，气温 25°C，湿度 60%%", location))
            .build();

        // 工具2：查询时间
        Tool timeTool = Tool.builder()
            .name("get_time")
            .params("city: String")
            .description("获取指定城市的当前时间，输入城市名称")
            .func(city -> String.format("%s 当前时间：2024-03-15 14:30:00", city))
            .build();

        // 工具3：计算器
        Tool calcTool = Tool.builder()
            .name("calculator")
            .params("expression: String")
            .description("执行数学计算，输入数学表达式，如 '2 + 3 * 4'")
            .func(expr -> {
                // 实际生产中这里调用真实计算逻辑
                return "计算结果：42";
            })
            .build();

        System.out.println("=== 工具定义 ===");
        System.out.println("天气工具：" + weatherTool.getName() + " - " + weatherTool.getDescription());
        System.out.println("时间工具：" + timeTool.getName() + " - " + timeTool.getDescription());
        System.out.println("计算工具：" + calcTool.getName() + " - " + calcTool.getDescription());
    }

    /**
     * 完整 ReAct Agent 实现
     *
     * ReAct = Reasoning + Acting
     * 循环：Thought → Action → Observation → Thought → ... → Final Answer
     */
    @Test
    public void reactAgent() {

        // === 1. 定义 ReAct Prompt 模板 ===
        PromptTemplate prompt = PromptTemplate.fromTemplate(
            """
            尽你所能回答以下问题。你有以下工具可以使用：

            ${tools}

            请按以下格式回答：

            Question: 你必须回答的问题
            Thought: 思考是否已有足够信息回答问题
            Action: 要执行的动作，必须是 [${toolNames}] 之一
            Action Input: 动作的输入参数
            Observation: 动作的执行结果

            （可重复 Thought/Action/Action Input/Observation 循环，最多3次）

            Thought: 基于已收集信息，确定最终回答
            Final Answer: 对原始问题的最终回答

            开始！

            Question: ${input}
            Thought:
            """
        );

        // === 2. 注册工具 ===
        Tool getWeather = Tool.builder()
            .name("get_weather")
            .params("location: String")
            .description("获取城市天气信息，输入城市名称")
            .func(location -> String.format("%s 天气晴，气温 25°C", location))
            .build();

        Tool getTime = Tool.builder()
            .name("get_time")
            .params("city: String")
            .description("获取城市当前时间，输入城市名称")
            .func(city -> String.format("%s 当前时间 14:30", city))
            .build();

        List<Tool> tools = List.of(getWeather, getTime);
        prompt.withTools(tools); // 将工具信息注入 Prompt

        // === 3. 配置 LLM ===
        ChatOllama llm = ChatOllama.builder().model("llama3:8b").temperature(0f).build();

        // === 4. 处理器：截取 LLM 输出到 Observation 之前（防止 LLM 自己编造观察结果）===
        TranslateHandler<AIMessage, AIMessage> cutAtObservation = new TranslateHandler<>(llmResult -> {
            if (llmResult == null || StringUtils.isEmpty(llmResult.getContent())
                    || !llmResult.getContent().contains("Observation:")) {
                if (llmResult != null) {
                    System.out.println(llmResult.getContent());
                }
                return llmResult;
            }
            String prefix = llmResult.getContent().substring(0, llmResult.getContent().indexOf("Observation:"));
            System.out.println(prefix);
            llmResult.setContent(prefix);
            return llmResult;
        });

        // === 5. 处理器：解析 LLM 输出为结构化 Map（提取 Action 和 Action Input）===
        TranslateHandler<Map<String, String>, AIMessage> parseAction =
            new TranslateHandler<>(llmResult -> PromptUtil.stringToMap(llmResult.getContent()));

        // === 6. 循环退出条件：已得到 Final Answer 或达到最大次数 ===
        int maxIterations = 10;
        Function<Integer, Boolean> shouldContinue = i -> {
            Map<String, String> parsed = ContextBus.get().getResult(parseAction.getNodeId());
            return i < maxIterations
                && (parsed == null || (parsed.containsKey("Action") && parsed.containsKey("Action Input")));
        };

        // === 7. 处理器：执行工具调用，将结果追加到 Prompt ===
        Function<Object, Boolean> needsToolCall = map ->
            ((Map<String, String>) map).containsKey("Action") && ((Map<String, String>) map).containsKey("Action Input");

        TranslateHandler<Object, Map<String, String>> executeTool = new TranslateHandler<>(map -> {
            StringPromptValue promptValue = ContextBus.get().getResult(prompt.getNodeId());
            AIMessage cutResult = ContextBus.get().getResult(cutAtObservation.getNodeId());

            // 找到对应工具并执行
            Tool useTool = tools.stream()
                .filter(t -> t.getName().equalsIgnoreCase(((Map<String, String>) map).get("Action")))
                .findFirst().orElse(null);

            if (useTool == null) {
                promptValue.setText(promptValue.getText().trim() + "again");
                return promptValue;
            }

            String observation = (String) useTool.getFunc().apply(((Map<String, String>) map).get("Action Input"));
            System.out.println("Observation: " + observation);

            // 将 Observation 追加到 Prompt，形成下一轮推理的上下文
            String prefix = cutResult.getContent();
            String thoughtPart = prefix.substring(prefix.indexOf("Thought:") + 8).trim();
            String agentScratchpad = thoughtPart + "\nObservation:" + observation + "\nThought:";
            promptValue.setText(promptValue.getText().trim() + agentScratchpad);

            return promptValue;
        });

        // === 8. 处理器：提取最终答案 ===
        TranslateHandler<Object, Object> extractFinalAnswer = new TranslateHandler<>(input -> {
            ChatGeneration generation = (ChatGeneration) input;
            String content = generation.getText();
            if (content.contains("Final Answer:")) {
                int start = content.indexOf("Final Answer:") + 13;
                int end = content.indexOf("\n", start);
                generation.setText(end > 0
                    ? content.substring(start, end).trim()
                    : content.substring(start).trim());
            }
            return generation;
        });

        // === 9. 构建 Agent 链 ===
        ConsumerHandler<?> printStart = new ConsumerHandler<>(
            input -> System.out.println("\n> 进入 AgentExecutor 链...")
        );
        ConsumerHandler<?> printEnd = new ConsumerHandler<>(
            input -> System.out.println("> 链执行完成。")
        );

        FlowInstance agentChain = chainActor.builder()
            .next(printStart)
            .next(prompt)
            .loop(
                shouldContinue,     // 循环条件
                llm,                // LLM 推理
                chainActor.builder()
                    .next(cutAtObservation)  // 截断 Observation 之后的内容
                    .next(parseAction)       // 解析 Action/Action Input
                    .next(
                        Info.c(needsToolCall, executeTool),             // 需要调用工具
                        Info.c(input -> ContextBus.get().getResult(llm.getNodeId())) // 已有答案
                    )
                    .build()
            )
            .next(new StrOutputParser())
            .next(extractFinalAnswer)
            .next(printEnd)
            .build();

        // === 10. 执行 Agent ===
        ChatGeneration result = chainActor.invoke(agentChain, Map.of("input", "上海现在的天气怎么样？"));

        System.out.println("\n=== 最终答案 ===");
        System.out.println(result.getText());
    }

    /**
     * 使用 AgentExecutor 封装类实现同样的 ReAct Agent
     *
     * 对比 reactAgent()，这里只需要 3 步：
     * 1. 定义工具
     * 2. 构建 AgentExecutor
     * 3. 执行
     */
    @Test
    public void reactAgentWithExecutor() {

        // 1. 定义工具（与 reactAgent 完全相同）
        Tool getWeather = Tool.builder()
            .name("get_weather")
            .params("location: String")
            .description("获取城市天气信息，输入城市名称")
            .func(location -> String.format("%s 天气晴，气温 25°C", location))
            .build();

        Tool getTime = Tool.builder()
            .name("get_time")
            .params("city: String")
            .description("获取城市当前时间，输入城市名称")
            .func(city -> String.format("%s 当前时间 14:30", city))
            .build();

        // 2. 构建 AgentExecutor（ReAct 循环由框架处理）
        AgentExecutor agent = AgentExecutor.builder(chainActor)
            .llm(ChatAliyun.builder().model("qwen-plus").temperature(0f).build())
            .tools(getWeather, getTime)
            .maxIterations(10)
            .onThought(System.out::print)
            .onObservation(obs -> System.out.println("Observation: " + obs))
            .build();

        // 3. 执行
        ChatGeneration result = agent.invoke("上海现在的天气怎么样？");

        System.out.println("\n=== 最终答案 ===");
        System.out.println(result.getText());
    }

    /**
     * 工具定义类：用 @AgentTool 注解替代 Tool.builder()
     *
     * 单参数方法：Action Input 直接传字符串
     * 多参数方法：Action Input 须为 JSON，框架自动解析并注入各参数
     */
    static class CityTools {

        @AgentTool("获取城市天气信息，输入城市名称")
        public String getWeather(String location) {
            return String.format("%s 天气晴，气温 25°C", location);
        }

        @AgentTool("获取城市当前时间，输入城市名称")
        public String getTime(String city) {
            return String.format("%s 当前时间 14:30", city);
        }

        @AgentTool("订机票，需要出发城市、目的城市和日期")
        public String bookFlight(
                @Param("出发城市") String fromCity,
                @Param("目的城市") String toCity,
                @Param("日期，格式 YYYY-MM-DD") String date) {
            return String.format("已成功预订 %s → %s，日期 %s 的机票", fromCity, toCity, date);
        }
    }

    /**
     * 使用 @AgentTool 注解 + AgentExecutor：与 LangChain Python @tool 装饰器对齐
     *
     * 单参数：Action Input = 字符串，直接传入
     * 多参数：Action Input = JSON 对象，框架自动解析
     */
    @Test
    public void reactAgentWithToolAnnotation() {

        // 直接传工具类实例，框架自动扫描 @AgentTool 方法
        AgentExecutor agent = AgentExecutor.builder(chainActor)
            .llm(ChatAliyun.builder().model("qwen-plus").temperature(0f).build())
            .tools(new CityTools())
            .maxIterations(10)
            .onThought(System.out::print)
            .onObservation(obs -> System.out.println("Observation: " + obs))
            .build();

        // 单参数工具调用
        System.out.println("===== 单参数工具调用 =====");
        ChatGeneration result1 = agent.invoke("上海现在的天气怎么样？");
        System.out.println("\n=== 最终答案 ===");
        System.out.println(result1.getText());

        // 多参数工具调用
        System.out.println("\n===== 多参数工具调用 =====");
        ChatGeneration result2 = agent.invoke("帮我订一张明天从上海飞北京的机票，日期是2024-03-15");
        System.out.println("\n=== 最终答案 ===");
        System.out.println(result2.getText());
    }

    /**
     * 机票比价工具类
     *
     * 演示多步骤 ReAct 推理：
     * 1. 查询三家航司的机票价格（多次工具调用）
     * 2. 比较价格，找到最优选择
     * 3. 调用订票工具完成预订
     */
    static class FlightTools {

        // 模拟东方航空票价数据
        private static final java.util.Map<String, Integer> MU_PRICES = java.util.Map.of(
            "上海-北京", 980, "上海-广州", 1200, "上海-成都", 1450
        );
        // 模拟国航票价数据
        private static final java.util.Map<String, Integer> CA_PRICES = java.util.Map.of(
            "上海-北京", 1150, "上海-广州", 1080, "上海-成都", 1380
        );
        // 模拟南航票价数据
        private static final java.util.Map<String, Integer> CZ_PRICES = java.util.Map.of(
            "上海-北京", 860, "上海-广州", 1320, "上海-成都", 1560
        );

        @AgentTool("查询东方航空（MU）的机票价格")
        public String queryMuFlight(
                @Param("出发城市") String fromCity,
                @Param("目的城市") String toCity,
                @Param("日期，格式 YYYY-MM-DD") String date) {
            String route = fromCity + "-" + toCity;
            Integer price = MU_PRICES.get(route);
            if (price == null) return String.format("东方航空暂无 %s 航线", route);
            return String.format("东方航空（MU）%s %s → %s，票价 ¥%d", date, fromCity, toCity, price);
        }

        @AgentTool("查询中国国际航空（CA）的机票价格")
        public String queryCaFlight(
                @Param("出发城市") String fromCity,
                @Param("目的城市") String toCity,
                @Param("日期，格式 YYYY-MM-DD") String date) {
            String route = fromCity + "-" + toCity;
            Integer price = CA_PRICES.get(route);
            if (price == null) return String.format("国航暂无 %s 航线", route);
            return String.format("中国国航（CA）%s %s → %s，票价 ¥%d", date, fromCity, toCity, price);
        }

        @AgentTool("查询南方航空（CZ）的机票价格")
        public String queryCzFlight(
                @Param("出发城市") String fromCity,
                @Param("目的城市") String toCity,
                @Param("日期，格式 YYYY-MM-DD") String date) {
            String route = fromCity + "-" + toCity;
            Integer price = CZ_PRICES.get(route);
            if (price == null) return String.format("南航暂无 %s 航线", route);
            return String.format("南方航空（CZ）%s %s → %s，票价 ¥%d", date, fromCity, toCity, price);
        }

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

    /**
     * 机票查询比价订票完整 Agent 演示
     *
     * Agent 自主完成：
     * Step 1: 调用 query_mu_flight 查询东方航空票价
     * Step 2: 调用 query_ca_flight 查询国航票价
     * Step 3: 调用 query_cz_flight 查询南航票价
     * Step 4: 分析比较，选出最低价
     * Step 5: 调用 book_flight 完成订票
     */
    @Test
    public void flightCompareAndBook() {

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

        System.out.println("\n=== 最终答案 ===");
        System.out.println(result.getText());
    }

    /**
     * 旅行规划助手工具类
     *
     * 模拟真实旅游场景所需的工具：
     * - 查天气：判断城市是否适合出行
     * - 查机票：获取当前价格决定出行顺序
     * - 查酒店：获取目的地住宿均价
     */
    static class TravelTools {

        @AgentTool("查询城市天气是否适合旅游，输入城市名称")
        public String getCityWeather(String city) {
            return switch (city) {
                case "成都" -> "成都：多云，气温18°C，湿度75%，适合出行";
                case "西安" -> "西安：晴，气温22°C，湿度40%，非常适合出行";
                case "桂林" -> "桂林：小雨，气温20°C，湿度90%，携带雨具";
                case "三亚" -> "三亚：晴，气温30°C，湿度70%，阳光充足";
                default     -> city + "：天气良好，适合出行";
            };
        }

        @AgentTool("查询出发城市到目的城市的机票价格（元）")
        public String getFlightPrice(
                @Param("出发城市") String from,
                @Param("目的城市") String to) {
            String route = from + "-" + to;
            return switch (route) {
                case "上海-成都" -> "上海→成都：最低价 ¥680，推荐航班 MU5137 09:00出发";
                case "上海-西安" -> "上海→西安：最低价 ¥420，推荐航班 CA8901 07:30出发";
                case "上海-桂林" -> "上海→桂林：最低价 ¥550，推荐航班 CZ6102 10:15出发";
                case "上海-三亚" -> "上海→三亚：最低价 ¥890，推荐航班 HU7803 08:00出发";
                default          -> route + "：暂无直飞，建议中转，参考价 ¥800";
            };
        }

        @AgentTool("查询城市酒店均价（元/晚）")
        public String getHotelPrice(String city) {
            return switch (city) {
                case "成都" -> "成都：三星均价 ¥280/晚，四星均价 ¥520/晚，推荐春熙路附近";
                case "西安" -> "西安：三星均价 ¥220/晚，四星均价 ¥450/晚，推荐钟楼附近";
                case "桂林" -> "桂林：三星均价 ¥200/晚，四星均价 ¥380/晚，推荐两江四湖景区";
                case "三亚" -> "三亚：三星均价 ¥480/晚，四星均价 ¥950/晚，推荐亚龙湾";
                default     -> city + "：均价 ¥300/晚";
            };
        }
    }

    /**
     * 演示：AgentExecutor 作为 chain 节点嵌套，构建旅行规划助手
     *
     * 实际应用场景：用户提出旅行需求，系统通过 chain 完成以下流程：
     *
     * Step1 - TranslateHandler：解析用户意图，生成结构化的 Agent 查询指令
     * Step2 - AgentExecutor  ：调用工具查询天气/机票/酒店，综合分析推荐方案
     * Step3 - TranslateHandler：将 Agent 输出格式化为用户友好的旅行报告
     *
     * 体现 AgentExecutor 作为 BaseRunnable 节点与普通 chain 节点无缝协作的能力。
     */
    @Test
    public void agentExecutorAsNode() {

        // 1. 构建旅行信息收集 Agent（继承 BaseRunnable，可直接作为 chain 节点）
        AgentExecutor travelAgent = AgentExecutor.builder(chainActor)
            .llm(ChatAliyun.builder().model("qwen-plus").temperature(0f).build())
            .tools(new TravelTools())
            .maxIterations(15)
            .onThought(System.out::print)
            .onObservation(obs -> System.out.println("\nObservation: " + obs))
            .build();

        // 2. 组装完整的旅行规划 chain
        //    TranslateHandler(意图解析) → AgentExecutor(信息收集) → TranslateHandler(报告生成)
        FlowInstance travelPlanChain = chainActor.builder()
            // Step1：将用户自然语言需求转成 Agent 能理解的查询指令
            .next(new TranslateHandler<>(userInput -> {
                System.out.println("=== Step1: 解析旅行需求 ===");
                System.out.println("用户需求：" + userInput);
                return "我从上海出发，计划去以下城市旅游：" + userInput +
                    "。请帮我：1)查询每个城市的天气；2)查询上海到各城市的机票价格；" +
                    "3)查询各城市酒店均价；4)综合以上信息，推荐最佳出行顺序和预算估算。";
            }))
            // Step2：AgentExecutor 作为节点，自主调用工具收集所有信息
            .next(travelAgent)
            // Step3：将 Agent 的分析结果包装成旅行报告
            .next(new TranslateHandler<>(output -> {
                System.out.println("\n=== Step3: 生成旅行报告 ===");
                String agentAnswer = ((ChatGeneration) output).getText();
                return "\n========== 旅行规划报告 ==========\n" + agentAnswer +
                    "\n==================================\n" +
                    "以上建议由 AI 旅行助手自动生成，请结合实际情况参考。";
            }))
            .build();

        // 3. 执行：用户只需输入想去的城市列表
        String report = chainActor.invoke(travelPlanChain, Map.of("input", "成都、西安、桂林"));

        System.out.println(report);
    }
}
