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
import org.salt.jlangchain.core.BaseRunnable;
import org.salt.jlangchain.core.ChainActor;
import org.salt.jlangchain.core.llm.ollama.ChatOllama;
import org.salt.jlangchain.core.parser.StrOutputParser;
import org.salt.jlangchain.core.prompt.string.PromptTemplate;
import org.salt.jlangchain.core.prompt.value.StringPromptValue;
import org.salt.jlangchain.core.tts.aliyun.AliyunTts;
import org.salt.jlangchain.core.tts.card.TtsCard;
import org.salt.jlangchain.core.tts.card.TtsCardChunk;
import org.salt.jlangchain.core.tts.doubao.DoubaoTts;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.junit4.SpringRunner;

import java.util.Map;
import java.util.concurrent.TimeoutException;

/**
 * 文章5：Java AI + TTS：让大模型开口说话
 *
 * 演示内容：
 * 1. ttsInvoke       - 同步调用：LLM 生成文本后转语音
 * 2. ttsDoubaoStream - 豆包 TTS 流式：文字和音频同步流出
 * 3. ttsAliyunStream - 阿里云 TTS 流式：文字和音频同步流出
 * 4. ttsWithPrompt   - 完整链路：Prompt → LLM → TTS
 */
@RunWith(SpringRunner.class)
@SpringBootTest(classes = TestApplication.class)
@SpringBootConfiguration
public class Article05LlmTts {

    @Autowired
    ChainActor chainActor;

    /**
     * 同步调用：LLM 生成文字后，一次性转为语音
     * 适合短文本、不需要实时播放的场景
     */
    @Test
    public void ttsInvoke() {
        BaseRunnable<StringPromptValue, ?> prompt = PromptTemplate.fromTemplate("用一段话介绍一下 ${topic}");
        ChatOllama llm = ChatOllama.builder().model("qwen2.5:0.5b").build();

        // 构建链：Prompt → LLM → 文本解析 → 豆包TTS
        FlowInstance chain = chainActor.builder()
            .next(prompt)
            .next(llm)
            .next(new StrOutputParser())  // 提取文本
            .next(new DoubaoTts())        // 文本 → 语音
            .build();

        TtsCard result = chainActor.invoke(chain, Map.of("topic", "人工智能"));

        System.out.println("=== TTS 同步调用结果 ===");
        System.out.println("文字内容：" + result.getText());
        System.out.println("音频数据长度：" + (result.getBase64() != null ? result.getBase64().length() : 0) + " bytes");
    }

    /**
     * 豆包 TTS 流式输出：文字和音频同步流出
     *
     * TtsCardChunk 中：
     * - isAudio() == false：文字 token，用于实时显示字幕
     * - isAudio() == true：音频数据，用于实时播放
     *
     * 这样可以实现"字幕和语音同步"的效果
     */
    @Test
    public void ttsDoubaoStream() throws TimeoutException {
        BaseRunnable<StringPromptValue, ?> prompt = PromptTemplate.fromTemplate("用三句话介绍 ${topic}");
        ChatOllama llm = ChatOllama.builder().model("qwen2.5:0.5b").build();

        FlowInstance chain = chainActor.builder()
            .next(prompt)
            .next(llm)
            .next(new StrOutputParser())
            .next(new DoubaoTts())  // 豆包 TTS
            .build();

        // stream() 返回流式 TtsCardChunk 迭代器
        TtsCardChunk result = chainActor.stream(chain, Map.of("topic", "Java编程"));

        System.out.println("=== 豆包 TTS 流式输出 ===");
        StringBuilder textSb = new StringBuilder();
        int audioPacketCount = 0;

        while (result.getIterator().hasNext()) {
            TtsCardChunk chunk = result.getIterator().next();

            if (!chunk.isAudio()) {
                // 文字 token：实时显示字幕
                textSb.append(chunk.getText());
                System.out.print("[文字] " + chunk.getText());
            } else {
                // 音频数据：实时播放
                audioPacketCount++;
                System.out.println("\n[音频包 #" + audioPacketCount + "] index=" + chunk.getIndex()
                    + ", 大小=" + (chunk.getBase64() != null ? chunk.getBase64().length() : 0) + " bytes");
            }
        }

        System.out.println("\n=== 完整文字内容 ===");
        System.out.println(textSb);
        System.out.println("收到音频包数量：" + audioPacketCount);
    }

    /**
     * 阿里云 TTS 流式输出
     * 接口与豆包完全一致，只需替换 TTS 组件
     */
    @Test
    public void ttsAliyunStream() throws TimeoutException {
        BaseRunnable<StringPromptValue, ?> prompt = PromptTemplate.fromTemplate("用三句话介绍 ${topic}");
        ChatOllama llm = ChatOllama.builder().model("qwen2.5:0.5b").build();

        FlowInstance chain = chainActor.builder()
            .next(prompt)
            .next(llm)
            .next(new StrOutputParser())
            .next(new AliyunTts())  // 只需替换这一行
            .build();

        TtsCardChunk result = chainActor.stream(chain, Map.of("topic", "机器学习"));

        System.out.println("=== 阿里云 TTS 流式输出 ===");
        StringBuilder textSb = new StringBuilder();
        int audioPacketCount = 0;

        while (result.getIterator().hasNext()) {
            TtsCardChunk chunk = result.getIterator().next();
            if (!chunk.isAudio()) {
                textSb.append(chunk.getText());
                System.out.print(chunk.getText());
            } else {
                audioPacketCount++;
            }
        }

        System.out.println("\n完整回答：" + textSb);
        System.out.println("音频包数量：" + audioPacketCount);
    }

    /**
     * 完整语音助手链路：带系统角色的对话 + TTS 输出
     * 模拟语音助手场景：用户提问 → AI 回答 → 语音播报
     */
    @Test
    public void voiceAssistant() throws TimeoutException {
        // 带角色设定的 Prompt
        BaseRunnable<StringPromptValue, ?> prompt = PromptTemplate.fromTemplate(
            """
            你是一个专业、友好的语音助手。请用简洁的语言（不超过3句话）回答以下问题。
            
            问题：${question}
            
            回答：
            """
        );

        ChatOllama llm = ChatOllama.builder().model("qwen2.5:0.5b").build();

        FlowInstance assistantChain = chainActor.builder()
            .next(prompt)
            .next(llm)
            .next(new StrOutputParser())
            .next(new DoubaoTts())
            .build();

        TtsCardChunk result = chainActor.stream(
            assistantChain,
            Map.of("question", "今天适合运动吗？")
        );

        System.out.println("=== 语音助手回答 ===");
        StringBuilder answer = new StringBuilder();
        while (result.getIterator().hasNext()) {
            TtsCardChunk chunk = result.getIterator().next();
            if (!chunk.isAudio()) {
                answer.append(chunk.getText());
                System.out.print(chunk.getText()); // 字幕
            }
            // chunk.getAudio() → 发送给前端播放
        }
        System.out.println("\n（同时伴随音频播放）");
    }
}
