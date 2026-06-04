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

import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.salt.jlangchain.TestApplication;
import org.salt.jlangchain.core.ChainActor;
import org.salt.jlangchain.core.llm.aliyun.ChatAliyun;
import org.salt.jlangchain.core.llm.deepseek.ChatDeepseek;
import org.salt.jlangchain.core.message.AIMessage;
import org.salt.jlangchain.core.message.AIMessageChunk;
import org.salt.jlangchain.core.prompt.string.PromptTemplate;
import org.salt.jlangchain.core.prompt.value.StringPromptValue;
import org.salt.jlangchain.core.BaseRunnable;
import org.salt.function.flow.FlowInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.junit4.SpringRunner;

import java.util.Map;
import java.util.concurrent.TimeoutException;

/**
 * 文章 29：推理模型集成——读取 DeepSeek-R1 与 Qwen3 的思考内容
 *
 * <ol>
 *   <li>testDeepSeekReasoningContent — DeepSeek-R1（deepseek-reasoner）推理内容读取</li>
 *   <li>testQwenThinkingContent      — Qwen3 开启 enable_thinking 后读取 think 内容</li>
 *   <li>testReasoningInChain         — 在 Prompt → LLM Chain 中获取推理内容</li>
 *   <li>testReasoningDisabled        — 关闭推理模式，reasoningContent 应为 null 或空</li>
 *   <li>testStreamingAnswer          — 流式同时获取推理内容与答案（每个 chunk 携带两个字段）</li>
 * </ol>
 *
 * <p>运行前置条件：
 * <ul>
 *   <li>testDeepSeekReasoningContent / testReasoningInChain：需 {@code DEEPSEEK_KEY}</li>
 *   <li>testQwenThinkingContent / testReasoningDisabled：需 {@code ALIYUN_KEY}</li>
 * </ul>
 */
@RunWith(SpringRunner.class)
@SpringBootTest(classes = TestApplication.class)
@SpringBootConfiguration
public class Article29ReasoningContent {

    @Autowired
    ChainActor chainActor;

    // ── 测试 1：DeepSeek-R1 推理内容 ─────────────────────────────────────────

    /**
     * deepseek-reasoner 是 DeepSeek R1 系列推理模型，回答前输出完整推理链。
     * j-langchain 将推理过程映射到 AIMessage.getReasoningContent()；
     * 最终答案通过 AIMessage.getContent() 读取。
     */
    @Test
    public void testDeepSeekReasoningContent() {
        ChatDeepseek llm = ChatDeepseek.builder()
                .model("deepseek-reasoner")
                .build();

        AIMessage result = llm.invoke("9.11 和 9.9 哪个更大？请一步一步推理。");

        System.out.println("[Final Answer]\n" + result.getContent());

        String reasoning = result.getReasoningContent();
        System.out.println("\n[Reasoning Content]\n" + reasoning);

        Assert.assertNotNull("DeepSeek-R1 应返回推理内容", reasoning);
        Assert.assertFalse("推理内容不应为空", reasoning.isBlank());
        Assert.assertFalse("最终答案不应为空", result.getContent().isBlank());
    }

    // ── 测试 2：Qwen3 enable_thinking 推理内容 ───────────────────────────────

    /**
     * Qwen3 系列模型通过 modelKwargs 传入 enable_thinking=true 开启推理模式，
     * 模型输出的 &lt;think&gt;...&lt;/think&gt; 推理过程被提取到 reasoningContent 字段。
     */
    @Test
    public void testQwenThinkingContent() {
        ChatAliyun llm = ChatAliyun.builder()
                .model("qwen3-235b-a22b")
                .modelKwargs(Map.of("enable_thinking", true))
                .build();

        AIMessage result = llm.invoke("请解释一下为什么 0.1 + 0.2 ≠ 0.3，以及如何在程序中处理这个问题。");

        System.out.println("[Final Answer]\n" + result.getContent());

        String reasoning = result.getReasoningContent();
        System.out.println("\n[Thinking Content]\n" + (reasoning != null ? reasoning : "(无)"));

        Assert.assertFalse("最终答案不应为空", result.getContent().isBlank());
    }

    // ── 测试 3：Prompt → LLM Chain 中访问推理内容 ────────────────────────────

    /**
     * 标准 Prompt → LLM 链：chainActor.invoke() 在链末为 LLM 时返回 AIMessage。
     * 推理内容通过 AIMessage.getReasoningContent() 读取，与直接调用 LLM 完全一致。
     *
     * <p>注意：如果链末接 StrOutputParser，返回的是 ChatGeneration，
     * 但此时推理内容已丢失（Parser 只保留最终答案文本）。
     */
    @Test
    public void testReasoningInChain() {
        BaseRunnable<StringPromptValue, ?> prompt = PromptTemplate.fromTemplate(
                "请用中文回答：${question}，需要展示完整的推理过程。"
        );

        ChatDeepseek llm = ChatDeepseek.builder()
                .model("deepseek-reasoner")
                .build();

        FlowInstance chain = chainActor.builder()
                .next(prompt)
                .next(llm)
                .build();

        AIMessage result = chainActor.invoke(chain,
                Map.of("question", "一个班级有 30 名学生，其中 60% 是女生，女生中有 50% 喜欢数学，共有多少名女生喜欢数学？"));

        System.out.println("[Final Answer]\n" + result.getContent());

        String reasoning = result.getReasoningContent();
        System.out.println("\n[Reasoning Content]\n" + reasoning);

        Assert.assertFalse("最终答案不应为空", result.getContent().isBlank());
        Assert.assertNotNull("Chain 中也应能获取推理内容", reasoning);
    }

    // ── 测试 4：关闭推理模式对比 ──────────────────────────────────────────────

    /**
     * 对比：Qwen3 关闭 thinking（enable_thinking=false）时，
     * reasoningContent 为 null 或空，响应速度更快、token 消耗更少。
     */
    @Test
    public void testReasoningDisabled() {
        ChatAliyun llm = ChatAliyun.builder()
                .model("qwen3-235b-a22b")
                .modelKwargs(Map.of("enable_thinking", false))
                .build();

        AIMessage result = llm.invoke("1 + 1 等于几？");

        System.out.println("[Final Answer]\n" + result.getContent());
        String reasoning = result.getReasoningContent();
        System.out.println("[Reasoning Content] " + (reasoning == null || reasoning.isBlank() ? "(空，符合预期)" : reasoning));

        Assert.assertFalse("最终答案不应为空", result.getContent().isBlank());
        System.out.println("[对比] 关闭 thinking 模式，无推理内容，响应更快");
    }

    // ── 测试 5：流式输出 ──────────────────────────────────────────────────────

    /**
     * 推理模型流式输出：推理内容与最终答案同时通过 {@code llm.stream()} 逐 token 获取。
     *
     * <p>每个 chunk 携带两个独立字段：
     * <ul>
     *   <li>{@code chunk.getReasoningContent()} — 当前推理 token（先于 content 出现）</li>
     *   <li>{@code chunk.getContent()}           — 当前答案 token（推理结束后出现）</li>
     * </ul>
     *
     * <p>流结束后，{@code stream.getReasoningContent()} 和 {@code stream.getContent()}
     * 分别是完整推理过程和完整答案的累积值。
     */
    @Test
    public void testStreamingAnswer() throws TimeoutException, InterruptedException {
        ChatDeepseek llm = ChatDeepseek.builder()
                .model("deepseek-reasoner")
                .build();

        String question = "简要说明为什么推理模型在数学题上比普通模型表现更好。";

        AIMessageChunk stream = llm.stream(question);

        System.out.println("[Streaming]");
        boolean inReasoning = true;
        StringBuilder reasoningSb = new StringBuilder();
        StringBuilder answerSb = new StringBuilder();
        while (stream.getIterator().hasNext()) {
            AIMessageChunk chunk = stream.getIterator().next();
            String reasoningDelta = chunk.getReasoningContent();
            String contentDelta = chunk.getContent();
            if (reasoningDelta != null && !reasoningDelta.isEmpty()) {
                reasoningSb.append(reasoningDelta);
                System.out.println("[Reasoning] "  + reasoningSb);
            }
            if (contentDelta != null && !contentDelta.isEmpty()) {
                answerSb.append(contentDelta);
                System.out.println("[Answer] " + answerSb);
            }
        }
        System.out.println();

        String fullReasoning = stream.getReasoningContent();
        String fullAnswer = stream.getContent();
        System.out.println("\n[Summary] 推理（" + (fullReasoning != null ? fullReasoning.length() : 0)
                + " 字符）+ 答案（" + (fullAnswer != null ? fullAnswer.length() : 0) + " 字符）均通过 stream 获取");

        Assert.assertNotNull("流式应返回推理内容", fullReasoning);
        Assert.assertFalse("推理内容不应为空", fullReasoning.isBlank());
        Assert.assertNotNull("流式应返回答案", fullAnswer);
        Assert.assertFalse("流式答案不应为空", fullAnswer.isBlank());
    }
}
