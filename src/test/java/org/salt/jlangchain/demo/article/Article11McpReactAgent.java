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

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.salt.function.flow.FlowInstance;
import org.salt.function.flow.Info;
import org.salt.function.flow.context.ContextBus;
import org.salt.jlangchain.TestApplication;
import org.salt.jlangchain.ai.common.param.AiChatInput;
import org.salt.jlangchain.ai.common.param.AiChatOutput;
import org.salt.jlangchain.config.JLangchainConfigTest;
import org.salt.jlangchain.core.BaseRunnable;
import org.salt.jlangchain.core.ChainActor;
import org.salt.jlangchain.core.handler.ConsumerHandler;
import org.salt.jlangchain.core.handler.TranslateHandler;
import org.salt.jlangchain.core.llm.aliyun.ChatAliyun;
import org.salt.jlangchain.core.message.AIMessage;
import org.salt.jlangchain.core.message.BaseMessage;
import org.salt.jlangchain.core.message.MessageType;
import org.salt.jlangchain.core.message.ToolMessage;
import org.salt.jlangchain.core.parser.StrOutputParser;
import org.salt.jlangchain.core.parser.generation.ChatGeneration;
import org.salt.jlangchain.core.prompt.chat.ChatPromptTemplate;
import org.salt.jlangchain.core.prompt.value.ChatPromptValue;
import org.salt.jlangchain.rag.tools.mcp.McpManager;
import org.salt.jlangchain.utils.JsonUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.junit4.SpringRunner;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * 文章 11：MCP Function-Calling ReAct（模型原生 ToolCall）
 */
@Slf4j
@RunWith(SpringRunner.class)
@SpringBootTest(classes = {TestApplication.class, JLangchainConfigTest.class})
@SpringBootConfiguration
public class Article11McpReactAgent {

    private static final ObjectMapper MAPPER = JsonUtil.getObjectMapper();
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    @Autowired
    private ChainActor chainActor;

    @Autowired
    private McpManager mcpManager;

    @Test
    public void mcpFunctionCallingLoop() {
        BaseRunnable<ChatPromptValue, ?> prompt = ChatPromptTemplate.fromMessages(
            List.of(
                BaseMessage.fromMessage(MessageType.SYSTEM.getCode(),
                    """
                    你是一名能够调用 MCP HTTP 工具的智能体，需要按以下顺序完成任务：
                    1) 调用 get_export_ip 获取公网 IP；
                    2) 将该 IP 传给 get_ip_location，获取城市、经纬度以及网络信息；
                    3) 使用 get_ip_location 返回的纬度/经度调用 get_weather_open_meteo，并设置 current_weather=true；
                    4) 根据所有 Observation，总结位置与天气（只输出结论，不暴露工具名称）。
                    - 工具只在必要时调用，每个工具最多执行一次，出错需说明原因，但仍尽量输出已有信息。
                    """),
                BaseMessage.fromMessage(MessageType.HUMAN.getCode(), "用户问题：${input}")
            )
        );

        List<AiChatInput.Tool> tools = mcpManager.manifestForInput().get("default");
        if (CollectionUtils.isEmpty(tools)) {
            throw new IllegalStateException("mcp.config.json 未配置 default 工具组");
        }

        ChatAliyun llm = ChatAliyun.builder()
            .model("qwen3.6-plus")
            .temperature(0f)
            .tools(tools)
            .build();

        int maxIterations = 5;
        Function<Integer, Boolean> shouldContinue = round -> {
            if (round >= maxIterations) {
                return false;
            }
            if (round == 0) {
                return true;
            }
            AIMessage lastAi = ContextBus.get().getResult(llm.getNodeId());
            return lastAi instanceof ToolMessage toolMessage
                && CollectionUtils.isNotEmpty(toolMessage.getToolCalls());
        };

        Function<Object, Boolean> needsToolExecution = aiMessage ->
            aiMessage instanceof ToolMessage toolMessage
                && CollectionUtils.isNotEmpty(toolMessage.getToolCalls());

        TranslateHandler<Object, AIMessage> executeMcpTool = new TranslateHandler<>(msg -> {
            ChatPromptValue promptValue = ContextBus.get().getResult(prompt.getNodeId());
            if (!(msg instanceof ToolMessage toolMessage)) {
                return msg;
            }
            List<AiChatOutput.ToolCall> toolCalls = toolMessage.getToolCalls();
            if (CollectionUtils.isEmpty(toolCalls)) {
                return toolMessage;
            }
            promptValue.getMessages().add(toolMessage);

            AiChatOutput.ToolCall call = toolCalls.get(0);
            Map<String, Object> args = parseArgs(call.getFunction().getArguments());
            String toolName = call.getFunction().getName();

            try {
                System.out.println("[ToolCall] " + toolName + " params -> " + JsonUtil.toJson(args));
                Object result = mcpManager.runForInput("default", toolName, args);
                String observation = result != null ? result.toString() : "工具无返回内容";
                System.out.println("[Observation] " + observation);
                appendToolMessage(prompt, call, observation);
            } catch (Exception e) {
                log.error("调用 MCP 工具 {} 失败: {}", toolName, e.getMessage(), e);
                appendToolMessage(prompt, call, "调用失败：" + e.getMessage());
            }
            return ContextBus.get().getResult(prompt.getNodeId());
        });

        ConsumerHandler<?> printStart = new ConsumerHandler<>(
            input -> System.out.println("\n> MCP Function-Calling ReAct 链开始执行...")
        );
        ConsumerHandler<?> printEnd = new ConsumerHandler<>(
            input -> System.out.println("> 链执行完成。")
        );

        FlowInstance chain = chainActor.builder()
            .next(printStart)
            .next(prompt)
            .loop(
                shouldContinue,
                llm,
                chainActor.builder()
                    .next(input -> {
                        log.debug("LLM 输出: {}", JsonUtil.toJson(input));
                        return input;
                    })
                    .next(
                        Info.c(needsToolExecution, executeMcpTool),
                        Info.c(input -> ContextBus.get().getResult(llm.getNodeId()))
                    )
                    .build()
            )
            .next(new StrOutputParser())
            .next(printEnd)
            .build();

        ChatGeneration finalAnswer = chainActor.invoke(chain, Map.of(
            "input", "不要询问额外信息，自动检测我的公网 IP，推断所在城市并告知当前天气后统一回复。"
        ));

        System.out.println("\n=== 最终回答 ===");
        System.out.println(finalAnswer.getText());
    }

    private Map<String, Object> parseArgs(String json) {
        try {
            return MAPPER.readValue(json, MAP_TYPE);
        } catch (IOException e) {
            log.warn("解析工具参数失败，将使用空参数: {}", e.getMessage());
            return Map.of();
        }
    }

    private void appendToolMessage(BaseRunnable<ChatPromptValue, ?> prompt,
                                   AiChatOutput.ToolCall call,
                                   String toolResult) {
        ChatPromptValue promptValue = ContextBus.get().getResult(prompt.getNodeId());
        promptValue.getMessages().add(
            BaseMessage.fromMessage(
                MessageType.TOOL.getCode(),
                toolResult,
                call.getFunction().getName(),
                call.getId()
            )
        );
    }
}
