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
import org.salt.jlangchain.TestApplication;
import org.salt.jlangchain.core.ChainActor;
import org.salt.jlangchain.core.agent.McpAgentExecutor;
import org.salt.jlangchain.core.llm.aliyun.ChatAliyun;
import org.salt.jlangchain.core.skill.Skill;
import org.salt.jlangchain.core.skill.SkillConfig;
import org.salt.jlangchain.core.skill.loader.ClasspathSkillConfigLoader;
import org.salt.jlangchain.core.subagent.SubAgent;
import org.salt.jlangchain.core.subagent.SubAgentConfig;
import org.salt.jlangchain.core.subagent.loader.ClasspathSubAgentConfigLoader;
import org.salt.jlangchain.rag.tools.Tool;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.junit4.SpringRunner;

import java.util.function.Function;

/**
 * 文章 23：SubAgent —— 拥有自有工具、内嵌 Skill 知识的独立 Agent 单元。
 *
 * <p>与 Skill 的区别：
 * <ul>
 *   <li>SubAgent 自带工具，不从父 Agent 借用</li>
 *   <li>SubAgent 将 SkillConfig 的工作流知识预注入 systemPrompt，而非作为可调用 tool</li>
 * </ul>
 *
 * <p>演示场景：
 * <ol>
 *   <li>独立运行 SubAgent（不挂 Master Agent）</li>
 *   <li>classpath AGENT.md 加载 + Master Agent 注册</li>
 *   <li>代码直接构造 SubAgentConfig（无文件依赖）</li>
 *   <li>model=inherit：SubAgent 继承 Master 的 LLM</li>
 *   <li>model + llmFactory：Master 通过工厂为 SubAgent 按名构建 LLM</li>
 *   <li>allowedTools：SubAgent 通过 tools 字段从 Master 借用工具</li>
 *   <li>SKILL 内嵌 SubAgent：agents/ 目录下的 agent 自动注册为 skill 内部工具</li>
 * </ol>
 */
@RunWith(SpringRunner.class)
@SpringBootTest(classes = TestApplication.class)
@SpringBootConfiguration
public class Article23SubAgent {

    @Autowired
    private ChainActor chainActor;

    // ── 共用工具 ──────────────────────────────────────────────────────────────

    static class TravelTools {

        public String getWeather(String city) {
            return switch (city) {
                case "成都" -> "成都：多云，18~26°C，下午有小雨";
                case "西安" -> "西安：晴，16~28°C";
                case "桂林" -> "桂林：阵雨，23~30°C";
                case "三亚" -> "三亚：晴，28~33°C，紫外线强";
                default -> city + "：晴到多云，18~30°C";
            };
        }

        public String getFlightPrice(String city) {
            return switch (city) {
                case "成都" -> "上海→成都：¥980（经济舱）";
                case "西安" -> "上海→西安：¥850，含10kg行李";
                case "桂林" -> "上海→桂林：¥1180，含20kg行李";
                case "三亚" -> "上海→三亚：¥1600，含15kg行李";
                default -> "上海→" + city + "：¥1200（经济舱）";
            };
        }

        public String getHotelPrice(String city) {
            return switch (city) {
                case "成都" -> "成都：三星¥280/晚，四星¥520/晚";
                case "西安" -> "西安：三星¥220/晚，四星¥450/晚";
                case "桂林" -> "桂林：三星¥200/晚，四星¥380/晚";
                case "三亚" -> "三亚：三星¥480/晚，四星¥950/晚";
                default -> city + "：均价¥300/晚";
            };
        }
    }

    // ── 测试 1：SubAgent 独立运行 ─────────────────────────────────────────────

    @Test
    public void testSubAgentStandalone() {
        TravelTools tools = new TravelTools();

        SubAgentConfig config = ClasspathSubAgentConfigLoader.fromClasspath("agents/travel-researcher");
        SubAgent researcher = SubAgent.from(config, chainActor)
                .llm(ChatAliyun.builder().model("qwen-plus").temperature(0f).build())
                .tools(
                    buildTool("get_weather",      "查询城市天气",   "city: String", tools::getWeather),
                    buildTool("get_flight_price", "查询机票价格",   "city: String", tools::getFlightPrice),
                    buildTool("get_hotel_price",  "查询酒店均价",   "city: String", tools::getHotelPrice)
                )
                .onToolCall(tc  -> System.out.println("[ToolCall]    " + tc))
                .onObservation(obs -> System.out.println("[Observation] " + obs))
                .build();

        String result = researcher.invoke("我想去三亚旅游，出发地上海");
        System.out.println("\n========== SubAgent 独立运行结果 ==========");
        System.out.println(result);
    }

    // ── 测试 2：AGENT.md 加载 + Master Agent 注册 ─────────────────────────────

    @Test
    public void testSubAgentWithMasterAgent() {
        TravelTools tools = new TravelTools();
        var llm = ChatAliyun.builder().model("qwen-plus").temperature(0f).build();

        SubAgentConfig config = ClasspathSubAgentConfigLoader.fromClasspath("agents/travel-researcher");
        SubAgent researcher = SubAgent.from(config, chainActor)
                .llm(llm)
                .tools(
                    buildTool("get_weather",      "查询城市天气", "city: String", tools::getWeather),
                    buildTool("get_flight_price", "查询机票价格", "city: String", tools::getFlightPrice),
                    buildTool("get_hotel_price",  "查询酒店均价", "city: String", tools::getHotelPrice)
                )
                .verbose(true)
                .build();

        McpAgentExecutor master = McpAgentExecutor.builder(chainActor)
                .llm(ChatAliyun.builder().model("qwen-plus").temperature(0f).build())
                .subAgent(researcher)
                .systemPrompt("你是旅行总助手，遇到旅行信息查询任务请使用 travel_researcher。")
                .onToolCall(tc  -> System.out.println("[master] ToolCall: " + tc))
                .onObservation(obs -> System.out.println("[master] Observation: " + obs))
                .build();

        String result = master.invoke("我想从上海出发去成都和西安，帮我查一下旅行信息").getText();
        System.out.println("\n========== Master + SubAgent 结果 ==========");
        System.out.println(result);
    }

    // ── 测试 3：代码构造 SubAgentConfig ──────────────────────────────────────

    @Test
    public void testSubAgentWithCodeConfig() {
        TravelTools tools = new TravelTools();

        SubAgentConfig config = SubAgentConfig.builder()
                .name("weather_flight_agent")
                .description("查询目的地天气和机票信息的专属 Agent")
                .systemPrompt("""
                        你是出行信息专家。
                        收到城市名后，依次调用 get_weather 和 get_flight_price，
                        最后整合结果输出简洁的出行参考。
                        """)
                .build();

        SubAgent agent = SubAgent.from(config, chainActor)
                .llm(ChatAliyun.builder().model("qwen-plus").temperature(0f).build())
                .tools(
                    buildTool("get_weather",      "查询城市天气", "city: String", tools::getWeather),
                    buildTool("get_flight_price", "查询机票价格", "city: String", tools::getFlightPrice)
                )
                .onToolCall(tc  -> System.out.println("[ToolCall]    " + tc))
                .onObservation(obs -> System.out.println("[Observation] " + obs))
                .build();

        String result = agent.invoke("我打算去桂林，帮我查一下");
        System.out.println(result);
    }

    // ── 测试 4：model=inherit —— SubAgent 继承 Master 的 LLM ─────────────────

    @Test
    public void testInheritModel() {
        TravelTools tools = new TravelTools();
        var masterLlm = ChatAliyun.builder().model("qwen-plus").temperature(0f).build();

        // AGENT.md: model: inherit，不需要在 builder 里指定 llm
        SubAgentConfig config = ClasspathSubAgentConfigLoader.fromClasspath("agents/weather-checker");
        // model=inherit → llm 由 master 在 build() 时注入，builder 里不设置 llm
        SubAgent weatherAgent = SubAgent.from(config, chainActor)
                .tools(buildTool("get_weather", "查询城市天气", "city: String", tools::getWeather))
                .onToolCall(tc  -> System.out.println("[weather_checker] ToolCall: " + tc))
                .onObservation(obs -> System.out.println("[weather_checker] Observation: " + obs))
                .build();

        McpAgentExecutor master = McpAgentExecutor.builder(chainActor)
                .llm(masterLlm)           // 这个 llm 会被注入到 model=inherit 的 SubAgent
                .subAgent(weatherAgent)
                .systemPrompt("你是旅行助手，天气查询任务请使用 weather_checker。")
                .onToolCall(tc  -> System.out.println("[master] ToolCall: " + tc))
                .onObservation(obs -> System.out.println("[master] Observation: " + obs))
                .build();

        String result = master.invoke("成都现在天气怎么样？").getText();
        System.out.println("\n========== model=inherit 结果 ==========");
        System.out.println(result);
    }

    // ── 测试 5：model + llmFactory —— Master 按模型名构建 LLM ────────────────

    @Test
    public void testLlmFactory() {
        TravelTools tools = new TravelTools();

        // SubAgent 在 config 里指定 model，不在 builder 里传 llm
        SubAgentConfig config = SubAgentConfig.builder()
                .name("flight_checker")
                .description("机票价格查询专员。当需要查询机票信息时使用。")
                .model("qwen-turbo")   // 由 master 的 llmFactory 解析
                .systemPrompt("你是机票查询专员，收到城市名后调用 get_flight_price，返回机票价格。")
                .build();

        SubAgent flightAgent = SubAgent.from(config, chainActor)
                // 不调用 .llm()，由 llmFactory 在 master build() 时解析
                .tools(buildTool("get_flight_price", "查询机票价格", "city: String", tools::getFlightPrice))
                .onToolCall(tc  -> System.out.println("[flight_checker] ToolCall: " + tc))
                .onObservation(obs -> System.out.println("[flight_checker] Observation: " + obs))
                .build();

        McpAgentExecutor master = McpAgentExecutor.builder(chainActor)
                .llm(ChatAliyun.builder().model("qwen-plus").temperature(0f).build())
                .llmFactory(modelName ->
                    ChatAliyun.builder().model(modelName).temperature(0f).build()
                )
                .subAgent(flightAgent)
                .systemPrompt("你是旅行助手，机票查询任务请使用 flight_checker。")
                .onToolCall(tc  -> System.out.println("[master] ToolCall: " + tc))
                .build();

        String result = master.invoke("帮我查一下上海到西安的机票").getText();
        System.out.println("\n========== llmFactory 结果 ==========");
        System.out.println(result);
    }

    // ── 测试 6：allowedTools —— SubAgent 从 Master 借用工具 ──────────────────

    @Test
    public void testAllowedToolsFromParent() {
        TravelTools tools = new TravelTools();
        var llm = ChatAliyun.builder().model("qwen-plus").temperature(0f).build();

        Tool weatherTool = buildTool("get_weather",      "查询城市天气", "city: String", tools::getWeather);
        Tool flightTool  = buildTool("get_flight_price", "查询机票价格", "city: String", tools::getFlightPrice);
        Tool hotelTool   = buildTool("get_hotel_price",  "查询酒店均价", "city: String", tools::getHotelPrice);

        // SubAgent 声明只借 get_weather 和 get_hotel_price，不需要 get_flight_price
        SubAgentConfig config = SubAgentConfig.builder()
                .name("accommodation_advisor")
                .description("住宿建议专员。查询天气和酒店价格，给出住宿建议。当需要住宿信息时使用。")
                .allowedTools(java.util.List.of("get_weather", "get_hotel_price"))
                .systemPrompt("""
                        你是住宿建议专员。
                        收到城市名后，依次调用 get_weather 和 get_hotel_price，
                        结合天气和酒店价格给出住宿选择建议。
                        """)
                .build();

        // SubAgent 自己没有 tools，全部来自 allowedTools 注入
        SubAgent advisor = SubAgent.from(config, chainActor)
                .llm(llm)
                .onToolCall(tc  -> System.out.println("[accommodation_advisor] ToolCall: " + tc))
                .onObservation(obs -> System.out.println("[accommodation_advisor] Observation: " + obs))
                .build();

        // Master 拥有全部 3 个工具，SubAgent 只会得到 allowedTools 里声明的两个
        McpAgentExecutor master = McpAgentExecutor.builder(chainActor)
                .llm(ChatAliyun.builder().model("qwen-plus").temperature(0f).build())
                .tools(weatherTool, flightTool, hotelTool)
                .subAgent(advisor)
                .systemPrompt("你是旅行总助手，住宿建议任务请使用 accommodation_advisor。")
                .onToolCall(tc  -> System.out.println("[master] ToolCall: " + tc))
                .build();

        String result = master.invoke("我想去桂林，帮我看看住哪里合适").getText();
        System.out.println("\n========== allowedTools 结果 ==========");
        System.out.println(result);
    }

    // ── 测试 7：SKILL 内嵌 SubAgent (agents/ 目录) ───────────────────────────

    @Test
    public void testSkillWithEmbeddedAgent() {
        TravelTools tools = new TravelTools();
        var llm = ChatAliyun.builder().model("qwen-plus").temperature(0f).build();

        Tool weatherTool = buildTool("get_weather",      "查询城市天气", "city: String", tools::getWeather);
        Tool flightTool  = buildTool("get_flight_price", "查询机票价格", "city: String", tools::getFlightPrice);
        Tool hotelTool   = buildTool("get_hotel_price",  "查询酒店均价", "city: String", tools::getHotelPrice);

        // travel-planner SKILL 现在包含 agents/budget-advisor.md
        // budget-advisor 声明 allowed-tools: [get_hotel_price]，会从 skill 内部 executor 借用
        SkillConfig config = ClasspathSkillConfigLoader.fromClasspath("skills/travel-planner");
        System.out.println("内嵌 agents 数量: " + (config.getAgents() != null ? config.getAgents().size() : 0));
        config.getAgents().forEach(a ->
            System.out.println("  - " + a.getName() + " allowedTools=" + a.getAllowedTools()));

        Skill travelSkill = Skill.from(config, chainActor)
                .llm(llm)
                .tools(weatherTool, flightTool, hotelTool)
                .verbose(true)
//                .onToolCall(tc  -> System.out.println("[travel_planner] ToolCall: " + tc))
//                .onObservation(obs -> System.out.println("[travel_planner] Observation: " + obs))
                .build();

        McpAgentExecutor master = McpAgentExecutor.builder(chainActor)
                .llm(ChatAliyun.builder().model("qwen-plus").temperature(0f).build())
                .skill(travelSkill)
                .systemPrompt("你是旅行总助手，旅行规划任务请使用 travel_planner 技能。")
                .onToolCall(tc  -> System.out.println("[master] ToolCall: " + tc))
                .build();

        String result = master.invoke("我想去三亚旅游3晚，帮我估算一下住宿预算").getText();
        System.out.println("\n========== SKILL 内嵌 SubAgent 结果 ==========");
        System.out.println(result);
    }

    // ── helper ───────────────────────────────────────────────────────────────

    private static Tool buildTool(String name, String desc, String params,
                                  Function<String, String> fn) {
        return Tool.builder()
                .name(name)
                .description(desc)
                .params(params)
                .func(input -> fn.apply(input.toString()))
                .build();
    }
}
