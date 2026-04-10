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
import org.salt.jlangchain.rag.tools.annotation.AgentTool;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.junit4.SpringRunner;

import java.util.Map;

/**
 * 文章 15：AgentExecutor 嵌入 chain —— 旅行规划助手。
 */
@RunWith(SpringRunner.class)
@SpringBootTest(classes = TestApplication.class)
@SpringBootConfiguration
public class Article15TravelAgent {

    @Autowired
    private ChainActor chainActor;

    static class TravelTools {

        @AgentTool("查询城市天气")
        public String getWeather(String city) {
            return switch (city) {
                case "成都" -> "成都天气：多云，18~26°C，下午有小雨";
                case "西安" -> "西安天气：晴，16~28°C";
                case "桂林" -> "桂林天气：阵雨，23~30°C，晚间有雷阵雨";
                case "三亚" -> "三亚天气：晴，28~33°C，紫外线很强";
                default -> city + "天气：晴到多云，18~30°C";
            };
        }

        @AgentTool("查询机票价格")
        public String getFlightPrice(String city) {
            return switch (city) {
                case "成都" -> "上海 → 成都：¥980，上海 → 成都（商务舱）¥2650";
                case "西安" -> "上海 → 西安：¥850，含10公斤行李";
                case "桂林" -> "上海 → 桂林：¥1180，含20公斤行李";
                case "三亚" -> "上海 → 三亚：¥1600，含15公斤行李";
                default -> "上海 → " + city + "：¥1200（经济舱)";
            };
        }

        @AgentTool("查询酒店均价")
        public String getHotelPrice(String city) {
            return switch (city) {
                case "成都" -> "成都：三星均价 ¥280/晚，四星均价 ¥520/晚，推荐春熙路附近";
                case "西安" -> "西安：三星均价 ¥220/晚，四星均价 ¥450/晚，推荐钟楼附近";
                case "桂林" -> "桂林：三星均价 ¥200/晚，四星均价 ¥380/晚，推荐两江四湖景区";
                case "三亚" -> "三亚：三星均价 ¥480/晚，四星均价 ¥950/晚，推荐亚龙湾";
                default -> city + "：均价 ¥300/晚";
            };
        }
    }

    @Test
    public void planTrip() {
        AgentExecutor travelAgent = AgentExecutor.builder(chainActor)
            .llm(ChatAliyun.builder().model("qwen-plus").temperature(0f).build())
            .tools(new TravelTools())
            .maxIterations(15)
            .onThought(System.out::print)
            .onObservation(obs -> System.out.println("\nObservation: " + obs))
            .build();

        FlowInstance travelPlanChain = chainActor.builder()
            .next(new TranslateHandler<>(userInput -> {
                System.out.println("=== Step1: 解析旅行需求 ===");
                System.out.println("用户需求：" + userInput);
                return "我从上海出发，计划去以下城市旅游：" + userInput +
                    "。请帮我：1)查询每个城市的天气；2)查询上海到各城市的机票价格；" +
                    "3)查询各城市酒店均价；4)综合以上信息，推荐最佳出行顺序和预算估算。";
            }))
            .next(travelAgent)
            .next(new TranslateHandler<>(output -> {
                System.out.println("\n=== Step3: 生成旅行报告 ===");
                String agentAnswer = ((ChatGeneration) output).getText();
                return "\n========== 旅行规划报告 ==========\n" + agentAnswer +
                    "\n==================================\n" +
                    "以上建议由 AI 旅行助手自动生成，请结合实际情况参考。";
            }))
            .build();

        String report = chainActor.invoke(travelPlanChain, Map.of("input", "成都、西安、桂林"));
        System.out.println(report);
    }
}
