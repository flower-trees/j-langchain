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

import org.apache.commons.lang3.StringUtils;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.salt.function.flow.FlowInstance;
import org.salt.function.flow.Info;
import org.salt.jlangchain.TestApplication;
import org.salt.jlangchain.core.BaseRunnable;
import org.salt.jlangchain.core.ChainActor;
import org.salt.jlangchain.core.llm.aliyun.ChatAliyun;
import org.salt.jlangchain.core.llm.doubao.ChatDoubao;
import org.salt.jlangchain.core.llm.moonshot.ChatMoonshot;
import org.salt.jlangchain.core.llm.ollama.ChatOllama;
import org.salt.jlangchain.core.llm.openai.ChatOpenAI;
import org.salt.jlangchain.core.parser.StrOutputParser;
import org.salt.jlangchain.core.parser.generation.ChatGeneration;
import org.salt.jlangchain.core.parser.generation.ChatGenerationChunk;
import org.salt.jlangchain.core.prompt.string.PromptTemplate;
import org.salt.jlangchain.core.prompt.value.StringPromptValue;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.junit4.SpringRunner;

import java.util.Map;
import java.util.concurrent.TimeoutException;

@RunWith(SpringRunner.class)
@SpringBootTest(classes = TestApplication.class)
@SpringBootConfiguration
public class ChainDemoTest {

    @Autowired
    ChainActor chainActor;

    @Test
    public void ChainStreamDemo() {

        BaseRunnable<StringPromptValue, ?> prompt = PromptTemplate.fromTemplate("tell me a joke about ${topic}");

        ChatOllama oll = ChatOllama.builder().model("qwen2.5:0.5b").build();

        StrOutputParser parser = new StrOutputParser();

        FlowInstance chain = chainActor.builder().next(prompt).next(oll).next(parser).build();

        ChatGenerationChunk result = chainActor.stream(chain, Map.of("topic", "dog"));

        StringBuilder sb = new StringBuilder();

        while (result.getIterator().hasNext()) {
            ChatGenerationChunk chunk = null;
            try {
                chunk = result.getIterator().next();
            } catch (TimeoutException e) {
                throw new RuntimeException(e);
            }
            if (StringUtils.isNotEmpty(chunk.toString())) {
                sb.append(chunk);
                System.out.println("answer:" + sb);
            }
        }
    }

    @Test
    public void ChainSwitchDemo() {

        BaseRunnable<StringPromptValue, ?> prompt = PromptTemplate.fromTemplate("who are you?");

        ChatOpenAI chatOpenAI = ChatOpenAI.builder().model("gpt-3.5-turbo").build();
        ChatOllama chatOllama = ChatOllama.builder().model("qwen2.5:0.5b").build();
        ChatAliyun chatAliyun = ChatAliyun.builder().model("qwq-32b-preview").build();
        ChatDoubao chatDoubao = ChatDoubao.builder().model("ep-20240611104225-2d4ww").build();
        ChatMoonshot chatMoonshot = ChatMoonshot.builder().model("moonshot-v1-8k").build();

        StrOutputParser parser = new StrOutputParser();

        FlowInstance chain = chainActor.builder().next(prompt).next(
                Info.c("vendor == null || vendor == 'ollama'", chatOllama),
                Info.c("vendor == 'chatgpt'", chatOpenAI),
                Info.c("vendor == 'doubao'", chatDoubao),
                Info.c("vendor == 'aliyun'", chatAliyun),
                Info.c("vendor == 'moonshot'", chatMoonshot)
        ).next(parser).build();

        ChatGenerationChunk result = chainActor.stream(chain, Map.of("vendor", "ollama"));

        StringBuilder sb = new StringBuilder();

        while (result.getIterator().hasNext()) {
            try {
                ChatGenerationChunk chunk = result.getIterator().next();
                sb.append(chunk);
                System.out.println("answer:" + sb);
            } catch (TimeoutException e) {
                throw new RuntimeException(e);
            }
        }

        ChatGeneration generation = chainActor.invoke(chain, Map.of("vendor", "ollama"));
        System.out.println("invoke answer:" + generation.getMessage().getContent());
    }
}
