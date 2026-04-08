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
import org.salt.jlangchain.rag.tools.mcp.McpManager;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.junit4.SpringRunner;

/**
 * 文章 13：McpAgentExecutor 混合注入 McpManager + McpClient
 *
 * <p>同一 Agent 同时挂载 HTTP API 工具（如 {@code get_export_ip}）与 NPX 文件系统工具，
 * 适合「先调网络 API，再读写本地文件」等多来源、多步任务。
 *
 * <p>建议先读文章 11、12 中的单源 {@link McpAgentExecutor} 示例。
 */
@RunWith(SpringRunner.class)
@SpringBootTest(classes = {TestApplication.class, JLangchainConfigTest.class})
@SpringBootConfiguration
public class Article13McpMixedAgent {

    @Autowired
    private McpManager mcpManager;

    @Autowired
    private McpClient mcpClient;

    @Autowired
    private ChainActor chainActor;

    @Test
    public void mcpMixedAgent() {
        McpAgentExecutor agent = McpAgentExecutor.builder(chainActor)
            .llm(ChatAliyun.builder().model("qwen3.6-plus").temperature(0f).build())
            .tools(mcpManager, "default")
            .tools(mcpClient, "filesystem")
            .systemPrompt("你是一个智能助手，可以调用工具获取网络信息和操作文件系统。\n" +
                          "当你完成用户的所有要求后，直接给出最终答案，不要再调用任何工具。")
            .maxIterations(8)
            .onToolCall(tc -> System.out.println(">> Tool: " + tc))
            .onObservation(obs -> System.out.println(">> Result: " + obs))
            .build();

        ChatGeneration result = agent.invoke(
            "帮我查一下公网 IP，然后把 IP 地址写入 /tmp/my_ip.txt 文件，读取文件内容确认写入成功");

        System.out.println("\n=== 最终答案 ===");
        System.out.println(result.getText());
    }
}
