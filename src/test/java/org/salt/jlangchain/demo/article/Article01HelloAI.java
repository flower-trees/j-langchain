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
import org.salt.jlangchain.core.llm.aliyun.ChatAliyun;
import org.salt.jlangchain.core.llm.ollama.ChatOllama;
import org.salt.jlangchain.core.message.AIMessageChunk;
import org.salt.jlangchain.core.parser.JsonOutputParser;
import org.salt.jlangchain.core.parser.StrOutputParser;
import org.salt.jlangchain.core.parser.generation.ChatGeneration;
import org.salt.jlangchain.core.prompt.string.PromptTemplate;
import org.salt.jlangchain.core.event.EventMessageChunk;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.junit4.SpringRunner;

import java.util.Map;
import java.util.concurrent.TimeoutException;

/**
 * 文章1：5分钟用 Java 构建你的第一个 AI 应用
 *
 * 演示内容：
 * 1. hello          - 最简单的 Prompt → LLM → Parser 三步链
 * 2. streamOutput   - 流式输出，实现打字机效果
 * 3. jsonOutput     - 结构化 JSON 输出
 * 4. eventMonitor   - 事件流监控，调试链路每一步
 * 5. localModel     - 使用本地 Ollama 模型，零成本运行
 */
@RunWith(SpringRunner.class)
@SpringBootTest(classes = TestApplication.class)
@SpringBootConfiguration
public class Article01HelloAI {

    @Autowired
    private ChainActor chainActor;

    /**
     * Step 1：三步构建第一个 AI 应用
     * Prompt 模板 → 大模型 → 输出解析器
     */
    @Test
    public void hello() {
        // 1. 定义 Prompt 模板，${topic} 是变量占位符
        PromptTemplate prompt = PromptTemplate.fromTemplate(
            "Tell me a joke about ${topic}"
        );

        // 2. 选择大模型（阿里云通义千问）
        ChatAliyun llm = ChatAliyun.builder()
            .model("qwen-plus")
            .build();

        // 3. 构建调用链：Prompt → LLM → Parser
        FlowInstance chain = chainActor.builder()
            .next(prompt)
            .next(llm)
            .next(new StrOutputParser())
            .build();

        // 4. 执行链，传入变量
        ChatGeneration result = chainActor.invoke(chain, Map.of("topic", "programmers"));

        System.out.println("=== AI 回答 ===");
        System.out.println(result.getText());
    }

    /**
     * Step 2：流式输出——打字机效果
     * 不等待大模型全部生成完毕，边生成边输出
     */
    @Test
    public void streamOutput() throws TimeoutException, InterruptedException {
        ChatAliyun llm = ChatAliyun.builder().model("qwen-plus").build();

        // stream() 方法返回流式 Chunk 迭代器
        AIMessageChunk chunk = llm.stream("用一句话解释什么是人工智能。");

        System.out.println("=== 流式输出（打字机效果）===");
        StringBuilder sb = new StringBuilder();
        while (chunk.getIterator().hasNext()) {
            String token = chunk.getIterator().next().getContent();
            sb.append(token);
            System.out.println(sb); // 逐 token 打印
        }
        System.out.println();
    }

    /**
     * Step 3：结构化 JSON 输出
     * 使用 JsonOutputParser 让 LLM 返回结构化数据
     */
    @Test
    public void jsonOutput() {
        ChatAliyun llm = ChatAliyun.builder().model("qwen-plus").build();

        FlowInstance chain = chainActor.builder()
            .next(llm)
            .next(new JsonOutputParser()) // 解析 JSON 格式输出
            .build();

        ChatGeneration result = chainActor.invoke(
            chain,
            "请以 JSON 格式列出3个编程语言，包含 name 和 year 字段。"
        );

        System.out.println("=== JSON 结构化输出 ===");
        System.out.println(result.getText());
    }

    /**
     * Step 4：事件流监控——调试链路每一步
     * streamEvent() 返回每个节点的输入输出事件，方便调试
     */
    @Test
    public void eventMonitor() throws TimeoutException {
        PromptTemplate prompt = PromptTemplate.fromTemplate("Tell me a joke about ${topic}");
        ChatAliyun llm = ChatAliyun.builder().model("qwen-plus").build();
        FlowInstance chain = chainActor.builder()
            .next(prompt)
            .next(llm)
            .next(new StrOutputParser())
            .build();

        // streamEvent 返回链中每个节点的事件
        EventMessageChunk events = chainActor.streamEvent(chain, Map.of("topic", "Java"));

        System.out.println("=== 事件流（调试信息）===");
        while (events.getIterator().hasNext()) {
            EventMessageChunk event = events.getIterator().next();
            System.out.println(event.toJson());
        }
    }

    /**
     * Step 5：使用本地 Ollama 模型，完全免费、无需联网
     * 前提：本地安装 Ollama 并拉取模型 ollama pull qwen2.5:0.5b
     */
    @Test
    public void localModel() {
        PromptTemplate prompt = PromptTemplate.fromTemplate("用中文回答：${question}");

        // 本地 Ollama 模型，无需 API Key
        ChatOllama llm = ChatOllama.builder().model("qwen2.5:0.5b").build();

        FlowInstance chain = chainActor.builder()
            .next(prompt)
            .next(llm)
            .next(new StrOutputParser())
            .build();

        ChatGeneration result = chainActor.invoke(
            chain,
            Map.of("question", "Java 和 Python 有什么区别？")
        );

        System.out.println("=== 本地模型回答 ===");
        System.out.println(result.getText());
    }
}
