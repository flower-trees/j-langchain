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
import org.salt.function.flow.Info;
import org.salt.jlangchain.TestApplication;
import org.salt.jlangchain.core.ChainActor;
import org.salt.jlangchain.core.agent.AgentExecutor;
import org.salt.jlangchain.core.handler.TranslateHandler;
import org.salt.jlangchain.core.llm.aliyun.ChatAliyun;
import org.salt.jlangchain.core.parser.generation.ChatGeneration;
import org.salt.jlangchain.rag.tools.annotation.AgentTool;
import org.salt.jlangchain.rag.tools.annotation.Param;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.junit4.SpringRunner;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;

/**
 * 文章 18：三 Agent 并行调研 + 综合规划——旅游规划助手。
 *
 * <p>景点、天气、费用三个专项 Agent 并行运行，结果汇总后交给综合规划 Agent 输出完整方案。
 */
@RunWith(SpringRunner.class)
@SpringBootTest(classes = TestApplication.class)
@SpringBootConfiguration
public class Article18ParallelTravelResearch {

    @Autowired
    private ChainActor chainActor;

    // ── 工具类：景点与体验 ──────────────────────────────────────────────────
    static class AttractionTools {

        @AgentTool("查询目的地热门景点")
        public String getTopAttractions(@Param("城市名称，如：京都") String city) {
            return switch (city) {
                case "京都" -> "京都热门景点：金阁寺、岚山竹林、伏见稻荷大社、清水寺、哲学之道、祇园花见小路";
                case "东京" -> "东京热门景点：浅草寺、新宿御苑、皇居、秋叶原、涩谷十字路口";
                case "大阪" -> "大阪热门景点：道顿堀、大阪城、心斋桥、通天阁、环球影城";
                default -> city + "热门景点：历史文化区、著名寺庙、传统市场、自然公园";
            };
        }

        @AgentTool("查询目的地特色美食")
        public String getLocalCuisine(@Param("城市名称，如：京都") String city) {
            return switch (city) {
                case "京都" -> "京都特色美食：汤豆腐、怀石料理、京野菜、抹茶甜点、鸭川河边料理、锦市场小吃";
                case "东京" -> "东京特色美食：寿司、拉面、天妇罗、焼き鳥、筑地海鲜";
                case "大阪" -> "大阪特色美食：章鱼烧、大阪烧、串炸、河豚料理、黑门市场";
                default -> city + "特色美食：当地传统料理、新鲜海鲜、街边小吃";
            };
        }
    }

    // ── 工具类：天气与时节 ──────────────────────────────────────────────────
    static class WeatherTools {

        @AgentTool("查询目的地指定月份天气")
        public String getWeatherByMonth(
                @Param("城市名称，如：京都") String city,
                @Param("出行月份，如：4月") String month) {
            if ("京都".equals(city)) {
                return switch (month) {
                    case "3月", "4月" -> "京都" + month + "：樱花季，气温 10~20°C，晴天为主，人流量极大，建议提前3个月预订住宿";
                    case "6月", "7月" -> "京都" + month + "：梅雨季，气温 24~34°C，湿热，观光人少，部分寺庙有特别活动";
                    case "11月" -> "京都11月：红叶季，气温 8~18°C，色彩最美，人流量仅次于樱花季";
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
            return city + month + "出行建议：提前规划行程，预留弹性时间，注意当地文化礼仪，避开本地节假日高峰";
        }
    }

    // ── 工具类：费用与预算 ──────────────────────────────────────────────────
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
                case "东京" -> "东京住宿（" + nights + "）：商务酒店 ¥400~800/晚，中端 ¥900~1800/晚";
                default -> city + "住宿（" + nights + "）：均价 ¥500~1000/晚";
            };
        }

        @AgentTool("查询目的地每日餐饮与交通费用")
        public String getDailyExpense(@Param("城市名称，如：京都") String city) {
            return switch (city) {
                case "京都" -> "京都每日开销：餐饮 ¥100~300（拉面¥60~便利店午餐¥50~怀石料理¥800+），" +
                    "交通 ¥50~100（巴士一日券¥70），景点门票 ¥50~200，合计约 ¥200~600/天";
                case "东京" -> "东京每日开销：餐饮 ¥80~250，交通 ¥80~150（西瓜卡），购物弹性大，合计约 ¥300~800/天";
                default -> city + "每日开销：约 ¥200~500/天";
            };
        }
    }

    // ── 工具类：综合规划（提供时间戳，让模型标注规划生成时间）─────────────────
    static class PlanTools {

        @AgentTool("获取当前规划生成时间")
        public String getPlanTimestamp() {
            return "规划生成时间：" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy年MM月dd日 HH:mm"));
        }
    }

    @Test
    public void parallelTravelResearch() {

        // ── Agent1：景点 Agent（负责景点与美食调研）──────────────────────────
        AgentExecutor attractionAgent = AgentExecutor.builder(chainActor)
            .llm(ChatAliyun.builder().model("qwen-plus").temperature(0f).build())
            .tools(new AttractionTools())
            .maxIterations(6)
            .onThought(t -> System.out.println("[景点Agent] " + t))
            .onObservation(obs -> System.out.println("[景点结果] " + obs))
            .build();

        // ── Agent2：天气 Agent（负责气候与出行建议）──────────────────────────
        AgentExecutor weatherAgent = AgentExecutor.builder(chainActor)
            .llm(ChatAliyun.builder().model("qwen-plus").temperature(0f).build())
            .tools(new WeatherTools())
            .maxIterations(6)
            .onThought(t -> System.out.println("[天气Agent] " + t))
            .onObservation(obs -> System.out.println("[天气结果] " + obs))
            .build();

        // ── Agent3：费用 Agent（负责机票、住宿、每日开销）─────────────────────
        AgentExecutor budgetAgent = AgentExecutor.builder(chainActor)
            .llm(ChatAliyun.builder().model("qwen-plus").temperature(0f).build())
            .tools(new BudgetTools())
            .maxIterations(8)
            .onThought(t -> System.out.println("[费用Agent] " + t))
            .onObservation(obs -> System.out.println("[费用结果] " + obs))
            .build();

        // ── Agent4：综合规划 Agent（汇总三路报告，生成完整行程）──────────────
        AgentExecutor synthesisAgent = AgentExecutor.builder(chainActor)
            .llm(ChatAliyun.builder().model("qwen-plus").temperature(0.7f).build())
            .tools(new PlanTools())
            .maxIterations(3)
            .onThought(t -> System.out.println("[综合Agent] " + t))
            .onObservation(obs -> System.out.println("[综合结果] " + obs))
            .build();

        // ── 并行流水线：5个节点，三个 Agent 并行占一个节点 ──────────────────
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
                @SuppressWarnings("unchecked")
                Map<String, Object> results = (Map<String, Object>) map;
                String attractionReport = ((ChatGeneration) results.get("attraction")).getText();
                String weatherReport    = ((ChatGeneration) results.get("weather")).getText();
                String budgetReport     = ((ChatGeneration) results.get("budget")).getText();

                System.out.println("\n--- 三路调研完成，移交综合规划 Agent ---");
                System.out.println("[景点报告]\n" + attractionReport);
                System.out.println("\n[天气报告]\n" + weatherReport);
                System.out.println("\n[费用报告]\n" + budgetReport);
                System.out.println("\n--- Agent4：开始综合规划 ---");

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
                System.out.println("\n--- 综合规划完成 ---");
                return "\n========== 旅行规划方案 ==========\n" + plan +
                    "\n==================================";
            }))

            .build();

        String result = chainActor.invoke(travelPlan, Map.of(
            "input", "京都，5天4夜，4月出发，预算适中"
        ));

        System.out.println(result);
    }
}
