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
import org.salt.jlangchain.TestApplication;
import org.salt.jlangchain.core.ChainActor;
import org.salt.jlangchain.core.llm.aliyun.ChatAliyun;
import org.salt.jlangchain.core.llm.ollama.ChatOllama;
import org.salt.jlangchain.core.message.AIMessage;
import org.salt.jlangchain.core.message.AIMessageChunk;
import org.salt.jlangchain.core.parser.StrOutputParser;
import org.salt.jlangchain.core.parser.generation.ChatGeneration;
import org.salt.jlangchain.core.prompt.string.PromptTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.junit4.SpringRunner;

import java.util.Map;
import java.util.concurrent.TimeoutException;

/**
 * 文章7：Java 接入多家大模型 API 实战对比
 *
 * 演示内容：
 * 1. ollamaLocal     - 本地 Ollama（免费，无网络，适合开发测试）
 * 2. aliyunQwen      - 阿里云通义千问（国内稳定，中文效果好）
 * 3. modelSwitcher   - 通过条件链动态切换模型
 * 4. modelFallback   - 模型降级：主模型失败时自动切换备用
 * 5. modelComparison - 同一问题对比多个模型的回答
 */
@RunWith(SpringRunner.class)
@SpringBootTest(classes = TestApplication.class)
@SpringBootConfiguration
public class Article07MultiModel {

    @Autowired
    ChainActor chainActor;

    /**
     * 本地 Ollama 模型
     * 特点：免费、无需网络、数据不出本地、适合开发测试
     * 前提：安装 Ollama 并拉取模型：ollama pull qwen2.5:0.5b
     */
    @Test
    public void ollamaLocal() throws TimeoutException {
        ChatOllama llm = ChatOllama.builder()
            .model("qwen2.5:0.5b")   // 模型名称（来自 ollama list）
            // .baseUrl("http://localhost:11434")  // 默认地址，可自定义
            .build();

        // 流式调用
        AIMessageChunk chunk = llm.stream("用一句话介绍 Java");
        StringBuilder sb = new StringBuilder();
        while (chunk.getIterator().hasNext()) {
            String token = chunk.getIterator().next().getContent();
            if (StringUtils.isNotEmpty(token)) {
                sb.append(token);
            }
        }
        System.out.println("=== Ollama 本地模型（qwen2.5:0.5b）===");
        System.out.println(sb);
    }

    /**
     * 阿里云通义千问
     * 特点：国内稳定、中文效果好、价格低廉
     * 需要：ALIYUN_KEY 环境变量
     */
    @Test
    public void aliyunQwen() {
        ChatAliyun llm = ChatAliyun.builder()
            .model("qwen-plus")  // qwen-turbo / qwen-plus / qwen-max
            .build();

        // 同步调用
        AIMessage result = llm.invoke("用一句话介绍 Java");

        System.out.println("=== 阿里云通义千问（qwen-plus）===");
        System.out.println(result.getContent());
    }

    /**
     * 模型动态切换
     * 通过链式条件，在运行时选择不同的模型
     * 场景：根据任务类型、用户等级、成本预算选择合适模型
     */
    @Test
    public void modelSwitcher() {
        ChatOllama freeModel = ChatOllama.builder().model("qwen2.5:0.5b").build();
        ChatAliyun paidModel = ChatAliyun.builder().model("qwen-plus").build();

        FlowInstance chain = chainActor.builder()
            .next(PromptTemplate.fromTemplate("${question}"))
            .next(
                Info.c("tier == 'free'", freeModel),    // 免费用户：本地模型
                Info.c("tier == 'paid'", paidModel),    // 付费用户：云端模型
                Info.c(freeModel)                        // 默认：免费模型
            )
            .next(new StrOutputParser())
            .build();

        System.out.println("=== 模型动态切换 ===");

        // 免费用户
        ChatGeneration freeResult = chainActor.invoke(chain, Map.of(
            "question", "什么是 Java 泛型？",
            "tier", "free"
        ));
        System.out.println("[免费模型] " + freeResult.getText());

        // 付费用户
        ChatGeneration paidResult = chainActor.invoke(chain, Map.of(
            "question", "什么是 Java 泛型？",
            "tier", "paid"
        ));
        System.out.println("[付费模型] " + paidResult.getText());
    }

    /**
     * 模型降级（Fallback）
     * 主模型失败时自动切换到备用模型
     * 场景：保障高可用，避免单一模型故障导致服务中断
     */
    @Test
    public void modelFallback() {
        // 主模型：阿里云（假设可能超时或报错）
        ChatAliyun primaryModel = ChatAliyun.builder().model("qwen-plus").build();
        // 备用模型：本地 Ollama（始终可用）
        ChatOllama fallbackModel = ChatOllama.builder().model("qwen2.5:0.5b").build();

        String question = "什么是 Spring Boot？";
        String answer;

        try {
            // 尝试主模型
            AIMessage result = primaryModel.invoke(question);
            answer = "[主模型] " + result.getContent();
        } catch (Exception e) {
            System.out.println("主模型失败，切换备用模型：" + e.getMessage());
            // 降级到备用模型
            AIMessage result = fallbackModel.invoke(question);
            answer = "[备用模型] " + result.getContent();
        }

        System.out.println("=== 模型降级结果 ===");
        System.out.println(answer);
    }

    /**
     * 多模型对比
     * 同一个问题同时发给多个模型，对比结果
     * 场景：评估模型效果、选型决策
     */
    @Test
    public void modelComparison() {
        String question = "用一句话解释什么是机器学习";

        ChatOllama ollamaLlm = ChatOllama.builder().model("qwen2.5:0.5b").build();
        ChatAliyun aliyunLlm = ChatAliyun.builder().model("qwen-plus").build();

        // 本地模型
        AIMessage ollamaResult = ollamaLlm.invoke(question);

        // 阿里云模型
        AIMessage aliyunResult = aliyunLlm.invoke(question);

        System.out.println("=== 多模型对比 ===");
        System.out.println("问题：" + question);
        System.out.println();
        System.out.println("【Ollama qwen2.5:0.5b】（本地）");
        System.out.println(ollamaResult.getContent());
        System.out.println();
        System.out.println("【阿里云 qwen-plus】");
        System.out.println(aliyunResult.getContent());
    }

    /**
     * 统一接口：不同模型用相同的链构建方式
     * 展示框架抽象层的价值：切换模型只需改一行代码
     */
    @Test
    public void unifiedInterface() {
        String template = "请用中文解释：${concept}";
        String input = "什么是 RESTful API？";

        // 方式1：本地模型
        FlowInstance localChain = chainActor.builder()
            .next(PromptTemplate.fromTemplate(template))
            .next(ChatOllama.builder().model("qwen2.5:0.5b").build())  // ← 只改这一行
            .next(new StrOutputParser())
            .build();

        // 方式2：云端模型（注释掉上面，取消注释下面即可）
        // FlowInstance cloudChain = chainActor.builder()
        //     .next(PromptTemplate.fromTemplate(template))
        //     .next(ChatAliyun.builder().model("qwen-plus").build())   // ← 只改这一行
        //     .next(new StrOutputParser())
        //     .build();

        ChatGeneration result = chainActor.invoke(localChain, Map.of("concept", input));

        System.out.println("=== 统一接口示例 ===");
        System.out.println(result.getText());
    }
}
