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
 * <p>三种演示场景：
 * <ol>
 *   <li>独立运行 SubAgent（不挂 Master Agent）</li>
 *   <li>classpath AGENT.md 加载 + Master Agent 注册</li>
 *   <li>代码直接构造 SubAgentConfig（无文件依赖）</li>
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
