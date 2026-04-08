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
import org.salt.jlangchain.core.agent.AgentExecutor;
import org.salt.jlangchain.core.llm.aliyun.ChatAliyun;
import org.salt.jlangchain.core.parser.generation.ChatGeneration;
import org.salt.jlangchain.rag.tools.annotation.AgentTool;
import org.salt.jlangchain.rag.tools.annotation.Param;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.junit4.SpringRunner;

import java.util.Map;

/**
 * 文章 10：多步骤 ReAct —— 航司比价订票。
 *
 * 将「航司工具 + AgentExecutor」集中在一个示例类中，完整展示
 * 查询东方/国航/南航 → 对比价格 → 订票的多步推理链。
 */
@RunWith(SpringRunner.class)
@SpringBootTest(classes = TestApplication.class)
@SpringBootConfiguration
public class Article10FlightAgent {

    @Autowired
    private ChainActor chainActor;

    /**
     * 航司工具集合：三个查询工具 + 一个订票工具。
     */
    static class FlightTools {

        private static final Map<String, Integer> MU_PRICES = Map.of(
                "上海-北京", 980, "上海-广州", 1200, "上海-成都", 1450
        );
        private static final Map<String, Integer> CA_PRICES = Map.of(
                "上海-北京", 1150, "上海-广州", 1080, "上海-成都", 1380
        );
        private static final Map<String, Integer> CZ_PRICES = Map.of(
                "上海-北京", 860, "上海-广州", 1320, "上海-成都", 1560
        );

        @AgentTool("查询东方航空（MU）的机票价格")
        public String queryMuFlight(
                @Param("出发城市") String fromCity,
                @Param("目的城市") String toCity,
                @Param("日期，格式 YYYY-MM-DD") String date) {
            String route = fromCity + "-" + toCity;
            Integer price = MU_PRICES.get(route);
            if (price == null) {
                return String.format("东方航空暂无 %s 航线", route);
            }
            return String.format("东方航空（MU）%s %s → %s，票价 ¥%d", date, fromCity, toCity, price);
        }

        @AgentTool("查询中国国际航空（CA）的机票价格")
        public String queryCaFlight(
                @Param("出发城市") String fromCity,
                @Param("目的城市") String toCity,
                @Param("日期，格式 YYYY-MM-DD") String date) {
            String route = fromCity + "-" + toCity;
            Integer price = CA_PRICES.get(route);
            if (price == null) {
                return String.format("国航暂无 %s 航线", route);
            }
            return String.format("中国国航（CA）%s %s → %s，票价 ¥%d", date, fromCity, toCity, price);
        }

        @AgentTool("查询南方航空（CZ）的机票价格")
        public String queryCzFlight(
                @Param("出发城市") String fromCity,
                @Param("目的城市") String toCity,
                @Param("日期，格式 YYYY-MM-DD") String date) {
            String route = fromCity + "-" + toCity;
            Integer price = CZ_PRICES.get(route);
            if (price == null) {
                return String.format("南航暂无 %s 航线", route);
            }
            return String.format("南方航空（CZ）%s %s → %s，票价 ¥%d", date, fromCity, toCity, price);
        }

        @AgentTool("确认订购机票，输入航司、出发城市、目的城市和日期")
        public String bookFlight(
                @Param("航司名称，如：东方航空、国航、南航") String airline,
                @Param("出发城市") String fromCity,
                @Param("目的城市") String toCity,
                @Param("日期，格式 YYYY-MM-DD") String date) {
            return String.format("✅ 订票成功！%s %s → %s，日期 %s，订单号 ORD-%d",
                    airline, fromCity, toCity, date, (long) (Math.random() * 900000) + 100000);
        }
    }

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
}
