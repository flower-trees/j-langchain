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
import org.salt.jlangchain.rag.tools.Tool;
import org.salt.jlangchain.rag.tools.annotation.AgentTool;
import org.salt.jlangchain.rag.tools.annotation.Param;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.junit4.SpringRunner;

/**
 * 文章9：AgentExecutor —— 用注解快速定义工具，一行代码启动 ReAct Agent
 *
 * 演示内容：
 * 1. reactAgentWithExecutor      - AgentExecutor 封装类替代手写 ReAct 循环
 * 2. reactAgentWithToolAnnotation - @AgentTool 注解定义工具（单参数 + 多参数）
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
}
