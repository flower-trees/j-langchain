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

package org.salt.jlangchain.core.agent;

import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.salt.jlangchain.TestApplication;
import org.salt.jlangchain.core.ChainActor;
import org.salt.jlangchain.core.llm.aliyun.ChatAliyun;
import org.salt.jlangchain.core.parser.generation.ChatGeneration;
import org.salt.jlangchain.rag.tools.Tool;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.junit4.SpringRunner;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

@RunWith(SpringRunner.class)
@SpringBootTest(classes = TestApplication.class)
@SpringBootConfiguration
public class McpAgentExecutorReturnLastToolResultTest {

    private static final String TOOL_RESULT = "Risk level: High\nRecommendation: require mutual written consent.";

    @Autowired
    ChainActor chainActor;

    static ChatAliyun qwenFlash() {
        return ChatAliyun.builder()
                .model("qwen3.6-flash-2026-04-16")
                .temperature(0f)
                .modelKwargs(Map.of("enable_thinking", false))
                .build();
    }

    @Test
    public void returnLastToolResultUsesObservationAfterModelFinishes() {
        AtomicInteger toolCalls = new AtomicInteger();
        AtomicReference<String> firstLlmPrompt = new AtomicReference<>("");
        Tool analyzeTool = Tool.builder()
                .name("analyze_clause")
                .description("Required tool. Analyze one contract clause and return the exact risk result.")
                .params("clause: String")
                .func(args -> {
                    toolCalls.incrementAndGet();
                    return TOOL_RESULT;
                })
                .build();

        ChatGeneration result = McpAgentExecutor.builder(chainActor)
                .llm(qwenFlash())
                .tools(analyzeTool)
                .systemPrompt("""
                        You are a tool-routing test agent.
                        You must call analyze_clause exactly once with the user-provided clause.
                        After receiving the tool result, finish without calling any other tool.
                        """)
                .returnLastToolResult(true)
                .maxIterations(4)
                .onLlm(prompt -> {
                    if (firstLlmPrompt.get().isBlank()) {
                        firstLlmPrompt.set(prompt);
                    }
                })
                .verbose(true)
                .build()
                .invoke("""
                        Analyze this clause using the analyze_clause tool:
                        Party A may unilaterally modify any contract clause, and Party B is deemed to consent if no objection is made within 5 days.
                        """);

        System.out.println("====result====\n" +result.getText());

        Assert.assertEquals(TOOL_RESULT, result.getText());
        Assert.assertEquals("tool should execute exactly once", 1, toolCalls.get());
//        Assert.assertTrue("system prompt should include the return-last-tool-result control rule",
//                firstLlmPrompt.get().contains("Only indicate whether execution succeeded or failed"));
    }
}
