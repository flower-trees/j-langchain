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
import org.salt.jlangchain.rag.tools.mcp.McpClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.junit4.SpringRunner;

/**
 * 文章 13：McpAgentExecutor + McpClient（NPX / 进程 MCP 服务器）
 *
 * <p>McpClient 从 {@code mcp.server.config.json} 加载 NPX MCP 服务器；本用例使用
 * {@code filesystem} 服务器，工具可操作配置的目录（如 {@code /tmp}）。
 *
 * <p>前置：文章 8 中 McpClient 与 {@code mcp.server.config.json} 的说明。
 */
@RunWith(SpringRunner.class)
@SpringBootTest(classes = {TestApplication.class, JLangchainConfigTest.class})
@SpringBootConfiguration
public class Article13McpClientAgent {

    @Autowired
    private McpClient mcpClient;

    @Autowired
    private ChainActor chainActor;

    @Test
    public void mcpClientAgent() {
        McpAgentExecutor agent = McpAgentExecutor.builder(chainActor)
            .llm(ChatAliyun.builder().model("qwen3.6-plus").temperature(0f).build())
            .tools(mcpClient, "filesystem")
            .systemPrompt("你是一个文件管理助手，可以浏览和读取 /tmp 目录中的文件。")
            .maxIterations(5)
            .onToolCall(tc -> System.out.println(">> Tool call: " + tc))
            .onObservation(obs -> System.out.println(">> Observation: " + obs))
            .build();

        ChatGeneration result = agent.invoke("列出 /tmp 目录下的所有文件，并告诉我有多少个文件");

        System.out.println("\n=== 最终答案 ===");
        System.out.println(result.getText());
    }
}
