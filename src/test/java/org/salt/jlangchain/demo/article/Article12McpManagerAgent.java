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
import org.salt.jlangchain.config.JLangchainConfigTest;
import org.salt.jlangchain.core.ChainActor;
import org.salt.jlangchain.core.agent.McpAgentExecutor;
import org.salt.jlangchain.core.llm.aliyun.ChatAliyun;
import org.salt.jlangchain.core.parser.generation.ChatGeneration;
import org.salt.jlangchain.rag.tools.mcp.McpManager;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.junit4.SpringRunner;

/**
 * 文章 12：McpAgentExecutor + McpManager（HTTP API 工具）
 *
 * <p>McpManager 从 {@code mcp.config.json} 加载 HTTP API 工具，转成 Tool 列表注入
 * {@link McpAgentExecutor}。模型通过 Function Calling 自主决定调用哪个工具。
 *
 * <p>前置：文章 8《在 Java AI 应用中集成 MCP 工具协议》中 McpManager 与 {@code mcp.config.json} 的说明。
 */
@RunWith(SpringRunner.class)
@SpringBootTest(classes = {TestApplication.class, JLangchainConfigTest.class})
@SpringBootConfiguration
public class Article12McpManagerAgent {

    @Autowired
    private McpManager mcpManager;

    @Autowired
    private ChainActor chainActor;

    @Test
    public void mcpManagerAgent() {
        McpAgentExecutor agent = McpAgentExecutor.builder(chainActor)
            .llm(ChatAliyun.builder().model("qwen3.6-plus").temperature(0f).build())
            .tools(mcpManager, "default")
            .systemPrompt("你是一个智能助手，可以调用工具获取信息后回答用户问题。")
            .maxIterations(5)
            .onToolCall(tc -> System.out.println(">> Tool call: " + tc))
            .onObservation(obs -> System.out.println(">> Observation: " + obs))
            .build();

        ChatGeneration result = agent.invoke("帮我查一下我的公网 IP 是什么？");

        System.out.println("\n=== 最终答案 ===");
        System.out.println(result.getText());
    }
}
