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
import org.salt.jlangchain.core.handler.ConsumerHandler;
import org.salt.jlangchain.core.handler.TranslateHandler;
import org.salt.jlangchain.core.llm.ollama.ChatOllama;
import org.salt.jlangchain.core.message.AIMessage;
import org.salt.jlangchain.core.parser.StrOutputParser;
import org.salt.jlangchain.core.parser.generation.ChatGeneration;
import org.salt.jlangchain.core.prompt.string.PromptTemplate;
import org.salt.jlangchain.core.prompt.value.StringPromptValue;
import org.salt.jlangchain.rag.tools.Tool;
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

}
