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

package org.salt.jlangchain.demo.flow;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.salt.function.flow.FlowInstance;
import org.salt.function.flow.Info;
import org.salt.jlangchain.TestApplication;
import org.salt.jlangchain.core.BaseRunnable;
import org.salt.jlangchain.core.ChainActor;
import org.salt.jlangchain.core.llm.aliyun.ChatAliyun;
import org.salt.jlangchain.core.llm.ollama.ChatOllama;
import org.salt.jlangchain.core.message.AIMessage;
import org.salt.jlangchain.core.message.AIMessageChunk;
import org.salt.jlangchain.core.parser.JsonOutputParser;
import org.salt.jlangchain.core.parser.StrOutputParser;
import org.salt.jlangchain.core.parser.generation.ChatGeneration;
import org.salt.jlangchain.core.prompt.string.PromptTemplate;
import org.salt.jlangchain.core.prompt.value.StringPromptValue;
import org.salt.jlangchain.core.event.EventMessageChunk;
import org.salt.jlangchain.core.tts.aliyun.AliyunTts;
import org.salt.jlangchain.core.tts.card.TtsCardChunk;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.junit4.SpringRunner;

import java.util.Map;
import java.util.concurrent.TimeoutException;

@RunWith(SpringRunner.class)
@SpringBootTest(classes = TestApplication.class)
@SpringBootConfiguration
public class MyFirstAIApp {

    @Autowired
    private ChainActor chainActor;

    @Test
    public void hello() {
        // 1. 创建 Prompt 模板
        PromptTemplate prompt = PromptTemplate.fromTemplate(
            "Tell me a joke about ${topic}"
        );

        // 2. 选择大模型（阿里云千问）
        ChatAliyun llm = ChatAliyun.builder()
            .model("qwen-plus")
            .build();

        // 3. 构建调用链
        FlowInstance chain = chainActor.builder()
            .next(prompt)
            .next(llm)
            .next(new StrOutputParser())
            .build();

        // 4. 执行并获取结果
        ChatGeneration result = chainActor.invoke(
            chain,
            Map.of("topic", "programmers")
        );

        System.out.println(result.getText());
    }

    /**
     * 动态路由 - 根据用户输入智能切换模型
     * vendor=ollama 使用本地 Ollama，vendor=aliyun 使用阿里云千问
     */
    @Test
    public void dynamicRouting() {
        PromptTemplate prompt = PromptTemplate.fromTemplate("Tell me a joke about ${topic}");
        ChatOllama ollamaModel = ChatOllama.builder().model("qwen2.5:0.5b").build();
        ChatAliyun qwenModel = ChatAliyun.builder().model("qwen-plus").build();

        FlowInstance chain = chainActor.builder()
            .next(prompt)
            .next(
                Info.c("vendor == 'ollama'", ollamaModel),
                Info.c("vendor == 'aliyun'", qwenModel),
                Info.c(input -> "Unsupported vendor")
            )
            .next(new StrOutputParser())
            .build();

        // 动态选择模型
        ChatGeneration result = chainActor.invoke(chain, Map.of("topic", "AI", "vendor", "aliyun"));
        System.out.println(result.getText());
    }

    /**
     * 并行执行 - 同时生成笑话和诗歌
     */
    @Test
    public void parallelExecution() {
        ChatOllama llm = ChatOllama.builder().model("qwen2.5:0.5b").build();
        BaseRunnable<StringPromptValue, ?> jokePrompt = PromptTemplate.fromTemplate("tell me a joke about ${topic}");
        BaseRunnable<StringPromptValue, ?> poemPrompt = PromptTemplate.fromTemplate("write a 2-line poem about ${topic}");

        FlowInstance jokeChain = chainActor.builder().next(jokePrompt).next(llm).build();
        FlowInstance poemChain = chainActor.builder().next(poemPrompt).next(llm).build();

        FlowInstance parallelChain = chainActor.builder()
            .concurrent(jokeChain, poemChain)
            .next(input -> {
                Map<String, Object> map = (Map<String, Object>) input;
                Object jokeObj = map.get(jokeChain.getFlowId());
                Object poemObj = map.get(poemChain.getFlowId());
                String joke = jokeObj instanceof AIMessage ? ((AIMessage) jokeObj).getContent() : String.valueOf(jokeObj);
                String poem = poemObj instanceof AIMessage ? ((AIMessage) poemObj).getContent() : String.valueOf(poemObj);
                return Map.of("joke", joke, "poem", poem);
            })
            .build();

        Map<String, String> result = chainActor.invoke(parallelChain, Map.of("topic", "cats"));
        System.out.println("Joke: " + result.get("joke"));
        System.out.println("Poem: " + result.get("poem"));
    }

    /**
     * 流式输出 - 打字效果
     */
    @Test
    public void streamingOutput() throws TimeoutException, InterruptedException {
        ChatAliyun llm = ChatAliyun.builder().model("qwen-plus").build();
        AIMessageChunk chunk = llm.stream("What is the meaning of life? answer in 1 sentence.");
        StringBuilder sb = new StringBuilder();
        while (chunk.getIterator().hasNext()) {
            String token = chunk.getIterator().next().getContent();
            sb.append(token);
            System.out.print(token);
        }
        System.out.println();
    }

    /**
     * JSON 结构化输出
     */
    @Test
    public void jsonStructuredOutput() {
        ChatAliyun llm = ChatAliyun.builder().model("qwen-plus").build();
        FlowInstance chain = chainActor.builder()
            .next(llm)
            .next(new JsonOutputParser())
            .build();

        ChatGeneration result = chainActor.invoke(chain, "List 3 countries with populations in JSON format.");
        System.out.println(result.getText());
    }

    /**
     * 事件流监控 - 调试神器
     */
    @Test
    public void eventStreamMonitoring() throws TimeoutException {
        PromptTemplate prompt = PromptTemplate.fromTemplate("Tell me a joke about ${topic}");
        ChatAliyun llm = ChatAliyun.builder().model("qwen-plus").build();
        FlowInstance chain = chainActor.builder().next(prompt).next(llm).next(new StrOutputParser()).build();

        EventMessageChunk events = chainActor.streamEvent(chain, Map.of("topic", "AI"));
        while (events.getIterator().hasNext()) {
            EventMessageChunk event = events.getIterator().next();
            System.out.println(event.toJson());
        }
    }

    /**
     * TTS 语音合成（带智能过滤）
     * 需配置 ALIYUN_TTS_KEY 及阿里云语音合成 AppKey
     */
    @Test
    public void ttsSpeechSynthesis() throws TimeoutException {
        BaseRunnable<StringPromptValue, ?> prompt = PromptTemplate.fromTemplate("tell me a joke about ${topic}");
        ChatAliyun llm = ChatAliyun.builder().model("qwen-plus").build();
        AliyunTts tts = new AliyunTts();
        tts.setVoice("xiaoyun");
        tts.setFormat("wav");

        FlowInstance chain = chainActor.builder()
            .next(prompt)
            .next(llm)
            .next(new StrOutputParser())
            .next(tts)
            .build();

        TtsCardChunk result = chainActor.stream(chain, Map.of("topic", "bears"));
        StringBuilder sb = new StringBuilder();
        while (result.getIterator().hasNext()) {
            TtsCardChunk chunk = result.getIterator().next();
            if (!chunk.isAudio()) {
                sb.append(chunk.getText());
                System.out.println("answer:" + sb);
            } else {
                System.out.println("audio chunk " + chunk.getIndex() + " received, content:" + chunk.getBase64().substring(0, 20) + "...");
            }
        }
    }
}
