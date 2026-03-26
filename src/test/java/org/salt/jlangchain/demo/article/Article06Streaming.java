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
import org.salt.jlangchain.TestApplication;
import org.salt.jlangchain.core.BaseRunnable;
import org.salt.jlangchain.core.ChainActor;
import org.salt.jlangchain.core.event.EventMessageChunk;
import org.salt.jlangchain.core.llm.ollama.ChatOllama;
import org.salt.jlangchain.core.message.AIMessageChunk;
import org.salt.jlangchain.core.parser.FunctionOutputParser;
import org.salt.jlangchain.core.parser.JsonOutputParser;
import org.salt.jlangchain.core.parser.StrOutputParser;
import org.salt.jlangchain.core.parser.generation.ChatGenerationChunk;
import org.salt.jlangchain.core.prompt.string.PromptTemplate;
import org.salt.jlangchain.core.prompt.value.StringPromptValue;
import org.salt.jlangchain.utils.JsonUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.junit4.SpringRunner;

import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeoutException;
import java.util.stream.Stream;

/**
 * 文章6：Java AI 应用的流式输出：从原理到实战
 *
 * 演示内容：
 * 1. basicStream        - LLM 直接流式输出（最基础）
 * 2. chainStream        - 链式流式输出
 * 3. streamWithStop     - 流式输出中途取消
 * 4. jsonStream         - 流式 JSON 输出
 * 5. functionStream     - 流式 + 自定义函数处理器
 * 6. eventStream        - 事件流：监控链路每一步
 * 7. eventFilter        - 事件流过滤（按名称、类型、标签）
 */
@RunWith(SpringRunner.class)
@SpringBootTest(classes = TestApplication.class)
@SpringBootConfiguration
public class Article06Streaming {

    @Autowired
    ChainActor chainActor;

    /**
     * 最基础的流式输出
     * LLM 直接 stream()，逐 token 打印
     */
    @Test
    public void basicStream() throws TimeoutException {
        ChatOllama llm = ChatOllama.builder().model("qwen2.5:0.5b").build();

        // stream() 立即返回，不等待 LLM 完成
        AIMessageChunk chunk = llm.stream("天空是什么颜色？用10个字以内回答。");

        System.out.println("=== 基础流式输出 ===");
        StringBuilder sb = new StringBuilder();
        while (chunk.getIterator().hasNext()) {
            String token = chunk.getIterator().next().getContent();
            if (StringUtils.isNotEmpty(token)) {
                sb.append(token);
                System.out.print(token);  // 逐 token 输出
            }
        }
        System.out.println("\n完整内容：" + sb);
    }

    /**
     * 链式流式输出
     * Prompt → LLM → StrOutputParser，整条链流式
     */
    @Test
    public void chainStream() throws TimeoutException {
        BaseRunnable<StringPromptValue, ?> prompt = PromptTemplate.fromTemplate("讲一个关于 ${topic} 的笑话");
        ChatOllama llm = ChatOllama.builder().model("qwen2.5:0.5b").build();

        FlowInstance chain = chainActor.builder()
            .next(prompt)
            .next(llm)
            .next(new StrOutputParser())
            .build();

        // chainActor.stream() 返回 ChatGenerationChunk
        ChatGenerationChunk chunk = chainActor.stream(chain, Map.of("topic", "程序员"));

        System.out.println("=== 链式流式输出 ===");
        StringBuilder sb = new StringBuilder();
        while (chunk.getIterator().hasNext()) {
            String token = chunk.getIterator().next().getText();
            sb.append(token);
            System.out.print(token);
        }
        System.out.println();
    }

    /**
     * 流式输出中途取消
     * 场景：用户点击"停止生成"按钮
     */
    @Test
    public void streamWithStop() throws TimeoutException {
        BaseRunnable<StringPromptValue, ?> prompt = PromptTemplate.fromTemplate("讲一个关于 ${topic} 的长故事");
        ChatOllama llm = ChatOllama.builder().model("qwen2.5:0.5b").build();

        FlowInstance chain = chainActor.builder()
            .next(prompt).next(llm).next(new StrOutputParser()).build();

        ChatGenerationChunk chunk = chainActor.stream(chain, Map.of("topic", "太空探索"));

        System.out.println("=== 流式输出（中途取消）===");
        int tokenCount = 0;
        while (chunk.getIterator().hasNext()) {
            String token = chunk.getIterator().next().getText();
            System.out.print(token);
            tokenCount++;

            // 模拟用户在第5个 token 后点击停止
            if (tokenCount >= 5) {
                chainActor.stop(chain);  // 取消生成
                System.out.println("\n[用户取消，已停止生成]");
                break;
            }
        }
    }

    /**
     * 流式 JSON 输出
     * 让 LLM 边生成 JSON 边解析，实现 JSON 流式
     */
    @Test
    public void jsonStream() throws TimeoutException {
        ChatOllama llm = ChatOllama.builder().model("qwen2.5:0.5b").build();

        FlowInstance chain = chainActor.builder()
            .next(llm)
            .next(new JsonOutputParser())  // 流式 JSON 解析
            .build();

        ChatGenerationChunk chunk = chainActor.stream(
            chain,
            "以 JSON 格式输出3个国家及其人口，格式：[{name, population}]"
        );

        System.out.println("=== 流式 JSON 输出 ===");
        while (chunk.getIterator().hasNext()) {
            System.out.println(chunk.getIterator().next());
        }
    }

    /**
     * 流式 + 自定义函数处理器
     * 在流式输出的每个 chunk 上执行自定义函数
     * 场景：流式解析 JSON，实时提取特定字段
     */
    @Test
    public void functionStream() throws TimeoutException {
        ChatOllama llm = ChatOllama.builder().model("qwen2.5:0.5b").build();

        FlowInstance chain = chainActor.builder()
            .next(llm)
            .next(new JsonOutputParser())
            .next(new FunctionOutputParser(this::extractCountryNames))  // 自定义处理函数
            .build();

        ChatGenerationChunk chunk = chainActor.stream(chain,
            """
            以 JSON 格式输出法国、西班牙、日本及其人口。
            格式：{"countries": {"France": 67000000, "Spain": 47000000, "Japan": 125000000}}
            """
        );

        System.out.println("=== 流式 + 自定义函数 ===");
        while (chunk.getIterator().hasNext()) {
            String text = chunk.getIterator().next().getText();
            if (StringUtils.isNotEmpty(text)) {
                System.out.println("提取到国家：" + text);
            }
        }
    }

    // 自定义处理函数：从 JSON chunk 中提取国家名
    private String extractCountryNames(String jsonChunk) {
        if (JsonUtil.isValidJson(jsonChunk)) {
            Map<?, ?> map = JsonUtil.fromJson(jsonChunk, Map.class);
            if (map != null && map.containsKey("countries")) {
                Map<?, ?> countries = (Map<?, ?>) map.get("countries");
                return String.join(", ", countries.keySet().stream().map(Object::toString).toList());
            }
        }
        return "";
    }

    /**
     * 事件流：监控链路每一个节点
     * 返回 on_chain_start / on_llm_stream / on_chain_end 等事件
     */
    @Test
    public void eventStream() throws TimeoutException {
        BaseRunnable<StringPromptValue, ?> prompt = PromptTemplate.fromTemplate("讲一个关于 ${topic} 的笑话");
        ChatOllama llm = ChatOllama.builder().model("qwen2.5:0.5b").build();
        FlowInstance chain = chainActor.builder()
            .next(prompt).next(llm).next(new StrOutputParser()).build();

        // streamEvent 返回链路事件流
        EventMessageChunk events = chainActor.streamEvent(chain, Map.of("topic", "狗"));

        System.out.println("=== 事件流（调试）===");
        while (events.getIterator().hasNext()) {
            EventMessageChunk event = events.getIterator().next();
            System.out.println(event.toJson());
        }
    }

    /**
     * 事件流过滤
     * 只关注特定节点的事件，减少噪音
     */
    @Test
    public void eventFilter() throws TimeoutException {
        ChatOllama llm = ChatOllama.builder().model("qwen2.5:0.5b").build();

        FlowInstance chain = chainActor.builder()
            .next(llm.withConfig(Map.of("run_name", "my_llm")))  // 给节点命名
            .next((new JsonOutputParser()).withConfig(Map.of(
                "run_name", "my_parser",
                "tags", List.of("my_chain")   // 给节点打标签
            )))
            .build();

        System.out.println("=== 按名称过滤（只看 my_parser 的事件）===");
        EventMessageChunk byName = chainActor.streamEvent(
            chain,
            "生成 JSON 数据",
            event -> List.of("my_parser").contains(event.getName())  // 过滤器
        );
        while (byName.getIterator().hasNext()) {
            System.out.println(byName.getIterator().next().toJson());
        }

        System.out.println("\n=== 按类型过滤（只看 LLM 相关事件）===");
        EventMessageChunk byType = chainActor.streamEvent(
            chain,
            "生成 JSON 数据",
            event -> List.of("llm").contains(event.getType())
        );
        while (byType.getIterator().hasNext()) {
            System.out.println(byType.getIterator().next().toJson());
        }

        System.out.println("\n=== 按标签过滤（只看 my_chain 标签的事件）===");
        EventMessageChunk byTag = chainActor.streamEvent(
            chain,
            "生成 JSON 数据",
            event -> Stream.of("my_chain").anyMatch(event.getTags()::contains)
        );
        while (byTag.getIterator().hasNext()) {
            System.out.println(byTag.getIterator().next().toJson());
        }
    }
}
