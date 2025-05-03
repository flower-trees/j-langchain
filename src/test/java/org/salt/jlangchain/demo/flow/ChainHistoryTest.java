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
import org.salt.jlangchain.TestApplication;
import org.salt.jlangchain.core.BaseRunnable;
import org.salt.jlangchain.core.ChainActor;
import org.salt.jlangchain.core.history.memory.MemoryHistoryReader;
import org.salt.jlangchain.core.history.memory.MemoryHistoryStorer;
import org.salt.jlangchain.core.llm.ollama.ChatOllama;
import org.salt.jlangchain.core.message.BaseMessage;
import org.salt.jlangchain.core.message.MessageType;
import org.salt.jlangchain.core.message.PlaceholderMessage;
import org.salt.jlangchain.core.parser.StrOutputParser;
import org.salt.jlangchain.core.parser.generation.ChatGeneration;
import org.salt.jlangchain.core.prompt.chat.ChatPromptTemplate;
import org.salt.jlangchain.core.prompt.value.ChatPromptValue;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.junit4.SpringRunner;

import java.util.List;
import java.util.Map;

@RunWith(SpringRunner.class)
@SpringBootTest(classes = TestApplication.class)
@SpringBootConfiguration
public class ChainHistoryTest {

    @Autowired
    ChainActor chainActor;

    @Test
    public void ChainHistory() {

        BaseRunnable<ChatPromptValue, ?> prompt = ChatPromptTemplate.fromMessages(
                List.of(
                        BaseMessage.fromMessage(MessageType.SYSTEM.getCode(), "You are a helpful assistant. Answer all questions in ${language}."),
                        PlaceholderMessage.builder().content("${messages}").build()
                )
        );

        ChatOllama oll = ChatOllama.builder().model("qwen2.5:0.5b").build();

        StrOutputParser parser = new StrOutputParser();

        Long user = 1001L;
        Long session = 2001L;

        MemoryHistoryReader reader = MemoryHistoryReader.builder().userId(user).sessionId(session).build();
        MemoryHistoryStorer storer = MemoryHistoryStorer.builder().userId(user).sessionId(session).build();

        FlowInstance chain = chainActor.builder()
                .next(prompt)
                .next(reader)
                .next(oll)
                .next(parser)
                .next(storer)
                .build();

        ChatGeneration chatGeneration1 = chainActor.invoke(chain,
                Map.of("messages", BaseMessage.fromMessage(MessageType.HUMAN.getCode(), "Hi! I'm Todd. I come from Beijing."),
                        "language", "Chinese"));
        System.out.println(chatGeneration1);

        ChatGeneration chatGeneration2 = chainActor.invoke(chain,
                Map.of("messages", BaseMessage.fromMessage(MessageType.HUMAN.getCode(), "What's my name?"),
                        "language", "English"));
        System.out.println(chatGeneration2);

        ChatGeneration chatGeneration3 = chainActor.invoke(chain,
                Map.of("messages", BaseMessage.fromMessage(MessageType.HUMAN.getCode(), "Where do I come from?"),
                        "language", "English"));
        System.out.println(chatGeneration3);
    }
}
