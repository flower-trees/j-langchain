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
import org.salt.jlangchain.rag.tools.Tool;
import org.salt.jlangchain.rag.tools.annotation.AgentTool;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.junit4.SpringRunner;

/**
 * 文章 22：Skill Agent —— 基于 SKILL.md 的技能化 Agent。
 *
 * <p>演示三种模式：
 * <ol>
 *   <li>classpath 加载 SKILL.md，Master Agent + Skill（Scene B 工具注入）</li>
 *   <li>代码直接构造 SkillConfig（无文件依赖，适合测试）</li>
 *   <li>Skill 独立运行（不挂 Master Agent）</li>
 * </ol>
 */
@RunWith(SpringRunner.class)
@SpringBootTest(classes = TestApplication.class)
@SpringBootConfiguration
public class Article22SkillAgent {

    @Autowired
    private ChainActor chainActor;

    // ── 共用工具（Master 和 Skill 共享同一实例）─────────────────────────────

    static class TravelTools {

        @AgentTool("查询城市天气")
        public String getWeather(String city) {
            return switch (city) {
                case "成都" -> "成都：多云，18~26°C，下午有小雨";
                case "西安" -> "西安：晴，16~28°C";
                case "桂林" -> "桂林：阵雨，23~30°C";
                case "三亚" -> "三亚：晴，28~33°C，紫外线强";
                default -> city + "：晴到多云，18~30°C";
            };
        }

        @AgentTool("查询机票价格")
        public String getFlightPrice(String city) {
            return switch (city) {
                case "成都" -> "上海→成都：¥980（经济舱）";
                case "西安" -> "上海→西安：¥850，含10kg行李";
                case "桂林" -> "上海→桂林：¥1180，含20kg行李";
                case "三亚" -> "上海→三亚：¥1600，含15kg行李";
                default -> "上海→" + city + "：¥1200（经济舱）";
            };
        }

        @AgentTool("查询酒店均价")
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

    // ── 测试 1：Classpath SKILL.md + Master Agent（Scene B）─────────────────

    @Test
    public void testSkillWithMasterAgent() {
        TravelTools tools = new TravelTools();
        Tool weatherTool = buildTool("get_weather", "查询城市天气", "city: String", tools::getWeather);
        Tool flightTool  = buildTool("get_flight_price", "查询机票价格", "city: String", tools::getFlightPrice);
        Tool hotelTool   = buildTool("get_hotel_price", "查询酒店均价", "city: String", tools::getHotelPrice);

        var llm = ChatAliyun.builder().model("qwen-plus").temperature(0f).build();

        // Load skill from classpath SKILL.md
        SkillConfig config = ClasspathSkillConfigLoader.fromClasspath("skills/travel-planner");
        Skill travelSkill = Skill.from(config, chainActor).llm(llm).build();

        // Master agent: owns all tools, skill borrows allowed-tools via Scene B injection
        McpAgentExecutor master = McpAgentExecutor.builder(chainActor)
                .llm(ChatAliyun.builder().model("qwen-plus").temperature(0f).build())
                .tools(weatherTool, flightTool, hotelTool)
                .skill(travelSkill)
                .systemPrompt("你是旅行规划总助手，遇到旅行规划任务请使用 travel_planner 技能。")
                .onToolCall(tc -> System.out.println("[ToolCall] " + tc))
                .onObservation(obs -> System.out.println("[Observation] " + obs))
                .build();

        String result = master.invoke("我想从上海出发，去成都和西安旅游，帮我规划一下").getText();
        System.out.println("\n========== 旅行规划结果 ==========");
        System.out.println(result);
    }

    // ── 测试 2：代码构造 SkillConfig（适合单元测试）─────────────────────────

    @Test
    public void testSkillWithCodeConfig() {
        TravelTools tools = new TravelTools();
        Tool weatherTool = buildTool("get_weather", "查询城市天气", "city: String", tools::getWeather);
        Tool flightTool  = buildTool("get_flight_price", "查询机票价格", "city: String", tools::getFlightPrice);

        SkillConfig config = SkillConfig.builder()
                .name("weather_flight_query")
                .description("查询目的地天气和机票信息")
                .allowedTools(java.util.List.of("get_weather", "get_flight_price"))
                .systemPrompt("""
                        你是出行信息助手。
                        收到城市名后，依次调用 get_weather 和 get_flight_price，
                        最后整合结果输出简洁的出行参考。
                        """)
                .build();

        var llm = ChatAliyun.builder().model("qwen-plus").temperature(0f).build();
        Skill skill = Skill.from(config, chainActor).llm(llm).build();

        McpAgentExecutor master = McpAgentExecutor.builder(chainActor)
                .llm(ChatAliyun.builder().model("qwen-plus").temperature(0f).build())
                .tools(weatherTool, flightTool)
                .skill(skill)
                .onToolCall(tc -> System.out.println("[ToolCall] " + tc))
                .onObservation(obs -> System.out.println("[Observation] " + obs))
                .build();

        String result = master.invoke("我打算去桂林，帮我查一下天气和机票").getText();
        System.out.println(result);
    }

    // ── 测试 3：Skill 独立运行（不挂 Master）────────────────────────────────

    @Test
    public void testSkillStandalone() {
        TravelTools tools = new TravelTools();
        Tool weatherTool = buildTool("get_weather", "查询城市天气", "city: String", tools::getWeather);
        Tool flightTool  = buildTool("get_flight_price", "查询机票价格", "city: String", tools::getFlightPrice);
        Tool hotelTool   = buildTool("get_hotel_price", "查询酒店均价", "city: String", tools::getHotelPrice);

        SkillConfig config = ClasspathSkillConfigLoader.fromClasspath("skills/travel-planner");
        Skill skill = Skill.from(config, chainActor)
                .llm(ChatAliyun.builder().model("qwen-plus").temperature(0f).build())
                .tools(weatherTool, flightTool, hotelTool)  // 直接传工具，不走 Scene B
                .build();

        String result = skill.invoke("我想去三亚旅游，出发地上海");
        System.out.println(result);
    }

    // ── helper ───────────────────────────────────────────────────────────────

    private static Tool buildTool(String name, String desc, String params,
                                  java.util.function.Function<String, String> fn) {
        return Tool.builder()
                .name(name)
                .description(desc)
                .params(params)
                .func(input -> fn.apply(input.toString()))
                .build();
    }
}
