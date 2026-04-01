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

import org.apache.commons.lang3.tuple.Pair;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.salt.function.flow.FlowInstance;
import org.salt.function.flow.Info;
import org.salt.function.flow.context.ContextBus;
import org.salt.jlangchain.TestApplication;
import org.salt.jlangchain.core.BaseRunnable;
import org.salt.jlangchain.core.ChainActor;
import org.salt.jlangchain.core.InvokeChain;
import org.salt.jlangchain.core.llm.aliyun.ChatAliyun;
import org.salt.jlangchain.core.llm.ollama.ChatOllama;
import org.salt.jlangchain.core.message.AIMessage;
import org.salt.jlangchain.core.parser.StrOutputParser;
import org.salt.jlangchain.core.parser.generation.ChatGeneration;
import org.salt.jlangchain.core.parser.generation.Generation;
import org.salt.jlangchain.core.prompt.chat.ChatPromptTemplate;
import org.salt.jlangchain.core.prompt.string.PromptTemplate;
import org.salt.jlangchain.core.prompt.value.ChatPromptValue;
import org.salt.jlangchain.core.prompt.value.StringPromptValue;
import org.salt.jlangchain.utils.JsonUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.junit4.SpringRunner;

import java.util.List;
import java.util.Map;

/**
 * 文章2：Java AI 应用的 5 种链式编排模式
 *
 * 演示内容：
 * 1. simpleChain   - 顺序链：最基础的线性流水线
 * 2. switchChain   - 条件链：根据条件选择不同分支
 * 3. composeChain  - 组合链：链套链，实现多步推理
 * 4. parallelChain - 并行链：多个子链同时执行
 * 5. routeChain    - 路由链：LLM 自动分类后路由
 * 6. dynamicChain  - 动态链：带上下文历史的 RAG 链
 */
@RunWith(SpringRunner.class)
@SpringBootTest(classes = TestApplication.class)
@SpringBootConfiguration
public class Article02ChainPatterns {

    @Autowired
    ChainActor chainActor;

    /**
     * 模式1：顺序链（Simple Chain）
     * 最基础的线性流水线：A → B → C
     */
    @Test
    public void simpleChain() {
        BaseRunnable<StringPromptValue, ?> prompt = PromptTemplate.fromTemplate("讲一个关于 ${topic} 的笑话");
        ChatOllama llm = ChatOllama.builder().model("qwen2.5:0.5b").build();

        FlowInstance chain = chainActor.builder()
            .next(prompt)
            .next(llm)
            .next(new StrOutputParser())
            .build();

        ChatGeneration result = chainActor.invoke(chain, Map.of("topic", "程序员"));
        System.out.println("=== 顺序链输出 ===");
        System.out.println(result.getText());
    }

    /**
     * 模式2：条件链（Switch Chain）
     * 根据输入条件，动态选择不同的处理分支
     * 典型场景：根据用户选择切换不同大模型
     */
    @Test
    public void switchChain() {
        BaseRunnable<StringPromptValue, ?> prompt = PromptTemplate.fromTemplate("讲一个关于 ${topic} 的笑话");
        ChatOllama ollamaModel = ChatOllama.builder().model("qwen2.5:0.5b").build();
        // ChatOpenAI openAIModel = ChatOpenAI.builder().model("gpt-4").build();

        FlowInstance chain = chainActor.builder()
            .next(prompt)
            .next(
                // 条件1：vendor == 'ollama' 时使用本地模型
                Info.c("vendor == 'ollama'", ollamaModel),
                // 条件2：其他情况返回默认提示
                Info.c(input -> "暂不支持该模型供应商")
            )
            .next(new StrOutputParser())
            .build();

        ChatGeneration result = chainActor.invoke(chain, Map.of("topic", "Java", "vendor", "ollama"));
        System.out.println("=== 条件链输出 ===");
        System.out.println(result.getText());
    }

    /**
     * 模式3：组合链（Compose Chain）
     * 链套链，将一个链的输出作为另一个链的输入
     * 典型场景：先生成内容，再对内容做二次分析
     */
    @Test
    public void composeChain() {
        ChatOllama qwen = ChatOllama.builder().model("qwen2.5:0.5b").build();
        ChatOllama llama = ChatOllama.builder().model("llama3:8b").build();
        StrOutputParser parser = new StrOutputParser();

        // 第一步：生成笑话
        BaseRunnable<StringPromptValue, ?> jokePrompt = PromptTemplate.fromTemplate("讲一个关于 ${topic} 的笑话");
        FlowInstance jokeChain = chainActor.builder().next(jokePrompt).next(qwen).next(parser).build();

        // 第二步：分析笑话是否好笑
        BaseRunnable<StringPromptValue, ?> analysisPrompt = PromptTemplate.fromTemplate("这个笑话好笑吗？请分析一下：${joke})");

        FlowInstance analysisChain = chainActor.builder()
            .next(new InvokeChain(jokeChain))                          // 内嵌第一个链
            .next(input -> Map.of("joke", ((Generation) input).getText())) // 转换输出格式
            .next(input -> { System.out.println("=== 笑话内容 ===\n" + ((Map) input).get("joke")); return input;})
            .next(analysisPrompt)
            .next(llama)
            .next(parser)
            .build();

        ChatGeneration result = chainActor.invoke(analysisChain, Map.of("topic", "程序员"));
        System.out.println("=== 组合链输出 ===");
        System.out.println(result.getText());
    }

    /**
     * 模式4：并行链（Parallel Chain）
     * 多个子链同时执行，最后合并结果
     * 典型场景：同时生成多种内容，或并发调用多个服务
     */
    @Test
    public void parallelChain() {
        ChatOllama llm = ChatOllama.builder().model("qwen2.5:0.5b").build();

        BaseRunnable<StringPromptValue, ?> jokePrompt = PromptTemplate.fromTemplate("讲一个关于 ${topic} 的笑话");
        BaseRunnable<StringPromptValue, ?> poemPrompt = PromptTemplate.fromTemplate("写一首关于 ${topic} 的两行诗");

        FlowInstance jokeChain = chainActor.builder().next(jokePrompt).next(llm).build();
        FlowInstance poemChain = chainActor.builder().next(poemPrompt).next(llm).build();

        FlowInstance parallelChain = chainActor.builder()
            .concurrent(jokeChain, poemChain)  // 两个链并行执行
            .next(input -> {
                Map<String, Object> map = (Map<String, Object>) input;
                Object jokeResult = map.get(jokeChain.getFlowId());
                Object poemResult = map.get(poemChain.getFlowId());
                String joke = jokeResult instanceof AIMessage ? ((AIMessage) jokeResult).getContent() : String.valueOf(jokeResult);
                String poem = poemResult instanceof AIMessage ? ((AIMessage) poemResult).getContent() : String.valueOf(poemResult);
                return Map.of("joke", joke, "poem", poem);
            })
            .build();

        Map<String, String> result = chainActor.invoke(parallelChain, Map.of("topic", "猫"));
        System.out.println("=== 并行链输出 ===");
        System.out.println("笑话：" + result.get("joke"));
        System.out.println("诗歌：" + result.get("poem"));
    }

    /**
     * 模式5：路由链（Route Chain）
     * 先用 LLM 对输入分类，再根据分类结果路由到对应的专业链
     * 典型场景：智能客服问题分发、多领域问答
     */
    @Test
    public void routeChain() {
        ChatOllama llm = ChatOllama.builder().model("qwen2.5:0.5b").build();

        // 分类链：判断问题属于哪个类别
        BaseRunnable<StringPromptValue, Object> classifyPrompt = PromptTemplate.fromTemplate(
            """
            将以下问题分类为 `技术`、`业务` 或 `其他` 之一，只返回分类词，不要其他内容。
            
            问题：${question}
            
            分类：
            """
        );
        FlowInstance classifyChain = chainActor.builder()
            .next(classifyPrompt).next(llm).next(new StrOutputParser()).build();

        // 技术专家链
        FlowInstance techChain = chainActor.builder()
            .next(input -> { System.out.println("=== 技术专家开始回答 ==="); return input; })
            .next(PromptTemplate.fromTemplate("你是技术专家，请回答：${question}"))
            .next(ChatOllama.builder().model("qwen2.5:0.5b").build())
            .build();

        // 业务专家链
        FlowInstance bizChain = chainActor.builder()
            .next(input -> { System.out.println("=== 业务专家开始回答 ==="); return input; })
            .next(PromptTemplate.fromTemplate("你是业务专家，请回答：${question}"))
            .next(ChatOllama.builder().model("qwen2.5:0.5b").build())
            .build();

        // 通用链
        FlowInstance generalChain = chainActor.builder()
            .next(input -> { System.out.println("=== 通用回答 ==="); return input; })
            .next(PromptTemplate.fromTemplate("请回答：${question}"))
            .next(ChatOllama.builder().model("qwen2.5:0.5b").build())
            .build();

        FlowInstance fullChain = chainActor.builder()
            .next(new InvokeChain(classifyChain))
            .next(input -> Map.of(
                "category", input.toString(),
                "question", ((Map<?, ?>) ContextBus.get().getFlowParam()).get("question")
            ))
            .next(input -> { System.out.println("=== 主题结果 ===\n" + ((Map) input).get("category")); return input; })
            .next(
                Info.c("category == '技术'", techChain),
                Info.c("category == '业务'", bizChain),
                Info.c(generalChain)
            )
            .build();

        AIMessage result = chainActor.invoke(fullChain, Map.of("question", "如何优化 Java 内存？"));
        System.out.println("=== 路由链输出 ===");
        System.out.println(result.getContent());
    }

    /**
     * 模式6：动态上下文链（Dynamic Chain）
     * 结合对话历史，将历史问题改写为独立问题后再回答
     * 典型场景：多轮对话问答
     */
    @Test
    public void dynamicChain() {
        ChatAliyun llm = ChatAliyun.builder().model("qwen-plus").build();

        // 上下文改写链：将"接上文的问题"改写为独立问题
        BaseRunnable<ChatPromptValue, Object> contextualizePrompt = ChatPromptTemplate.fromMessages(
            List.of(
                Pair.of("system", "根据对话历史，将最新问题改写为独立问题，只返回问题本身。"),
                Pair.of("placeholder", "${chatHistory}"),
                Pair.of("human", "${question}")
            )
        );

        FlowInstance contextualizeChain = chainActor.builder()
            .next(contextualizePrompt).next(llm).next(new StrOutputParser()).build();

        // 有历史时才改写，无历史直接透传
        FlowInstance contextualizeIfNeeded = chainActor.builder().next(
            Info.c("chatHistory != null", new InvokeChain(contextualizeChain)),
            Info.c(input -> Map.of("question", ((Map<String, String>) input).get("question")))
        ).build();

        // QA 链：基于上下文回答问题
        BaseRunnable<ChatPromptValue, Object> qaPrompt = ChatPromptTemplate.fromMessages(
            List.of(
                Pair.of("system", "根据以下上下文回答问题：\n\n${context}"),
                Pair.of("human", "${question}")
            )
        );

        FlowInstance fullChain = chainActor.builder()
            .all(
                Info.c(contextualizeIfNeeded),
                Info.c(input -> "印度尼西亚2024年人口约2.78亿").cAlias("retriever")
            )
            .next(input -> Map.of(
                "question", ContextBus.get().getResult(contextualizeIfNeeded.getFlowId()).toString(),
                "context", ContextBus.get().getResult("retriever")
            )).next(input -> { System.out.println("=== 根据上下文修改内容 ===\n" + JsonUtil.toJson(input)); return input;})
            .next(qaPrompt)
            .next(llm)
            .next(new StrOutputParser())
            .build();

        // 模拟多轮对话：第一轮问印尼，第二轮问"那中国呢"
        ChatGeneration result = chainActor.invoke(fullChain,
            Map.of(
                "question", "那印度呢",
                "chatHistory", List.of(
                    Pair.of("human", "印度尼西亚有多少人口"),
                    Pair.of("ai", "约2.78亿")
                )
            )
        );

        System.out.println("=== 动态上下文链输出 ===");
        System.out.println(JsonUtil.toJson(result.toString()));
    }
}
