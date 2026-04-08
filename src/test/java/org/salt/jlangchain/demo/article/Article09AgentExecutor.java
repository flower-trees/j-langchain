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

import org.junit.Test;
import org.junit.runner.RunWith;
import org.salt.function.flow.FlowInstance;
import org.salt.jlangchain.TestApplication;
import org.salt.jlangchain.core.ChainActor;
import org.salt.jlangchain.core.agent.AgentExecutor;
import org.salt.jlangchain.core.handler.TranslateHandler;
import org.salt.jlangchain.core.llm.aliyun.ChatAliyun;
import org.salt.jlangchain.core.parser.generation.ChatGeneration;
import org.salt.jlangchain.rag.tools.Tool;
import org.salt.jlangchain.rag.tools.annotation.AgentTool;
import org.salt.jlangchain.rag.tools.annotation.Param;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.junit4.SpringRunner;

import java.util.Map;

/**
 * 文章9：AgentExecutor —— 用注解快速定义工具，一行代码启动 ReAct Agent
 *
 * 演示内容：
 * 1. reactAgentWithExecutor      - AgentExecutor 封装类替代手写 ReAct 循环
 * 2. reactAgentWithToolAnnotation - @AgentTool 注解定义工具（单参数 + 多参数）
 * 3. flightCompareAndBook        - 多步推理：三家航司比价订票（配套文章 10，{@link Article10FlightTools}）
 * 4. agentExecutorAsNode         - AgentExecutor 作为 chain 节点嵌套（旅行规划）
 */
@RunWith(SpringRunner.class)
@SpringBootTest(classes = TestApplication.class)
@SpringBootConfiguration
public class Article09AgentExecutor {

    @Autowired
    ChainActor chainActor;

    // ─────────────────────────────────────────────────────────────────────────
    // 1. AgentExecutor 基础用法
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * 使用 AgentExecutor 封装类实现 ReAct Agent
     *
     * 对比手写 ReAct 循环，只需 3 步：
     * 1. 定义工具
     * 2. 构建 AgentExecutor
     * 3. 执行
     */
    @Test
    public void reactAgentWithExecutor() {

        // 1. 定义工具
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

    // ─────────────────────────────────────────────────────────────────────────
    // 2. @AgentTool 注解定义工具
    // ─────────────────────────────────────────────────────────────────────────

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

    // ─────────────────────────────────────────────────────────────────────────
    // 3. 多步推理：机票比价订票（工具类见 Article10FlightTools，文档见文章 10）
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * 机票查询比价订票完整 Agent 演示（配套文章 10）
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
            .tools(new Article10FlightTools())
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

    // ─────────────────────────────────────────────────────────────────────────
    // 4. AgentExecutor 作为 chain 节点嵌套
    // ─────────────────────────────────────────────────────────────────────────

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
     * AgentExecutor 作为 chain 节点嵌套，构建旅行规划助手
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
