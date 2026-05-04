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
import org.salt.jlangchain.core.history.memory.buffer.ConversationBufferMemoryReader;
import org.salt.jlangchain.core.history.memory.buffer.ConversationBufferMemoryStorer;
import org.salt.jlangchain.core.history.memory.bufferwindow.ConversationBufferWindowMemoryReader;
import org.salt.jlangchain.core.history.memory.bufferwindow.ConversationBufferWindowMemoryStorer;
import org.salt.jlangchain.core.history.memory.summary.ConversationSummaryMemoryReader;
import org.salt.jlangchain.core.history.memory.summary.ConversationSummaryMemoryStorer;
import org.salt.jlangchain.core.history.memory.summarybuffer.ConversationSummaryBufferMemoryReader;
import org.salt.jlangchain.core.history.memory.summarybuffer.ConversationSummaryBufferMemoryStorer;
import org.salt.jlangchain.core.history.storage.InMemoryConversationStorage;
import org.salt.jlangchain.core.llm.aliyun.ChatAliyun;
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

    // Shared prompt template and parser for all tests
    private BaseRunnable<ChatPromptValue, ?> buildPrompt() {
        return ChatPromptTemplate.fromMessages(
                List.of(
                        BaseMessage.fromMessage(MessageType.SYSTEM.getCode(), "You are a helpful assistant. Answer all questions to the point."),
                        PlaceholderMessage.builder().content("${messages}").build()
                )
        );
    }

    private ChatAliyun buildLlm() {
        return ChatAliyun.builder().model("qwen3.6-plus").build();
    }

    /**
     * ConversationBufferMemory: stores every turn without trimming.
     * Reader injects the last `limit` turns into the prompt.
     * Verifies that context from earlier turns is still accessible.
     */
    @Test
    public void testBufferMemory() {
        InMemoryConversationStorage storage = new InMemoryConversationStorage();
        long userId = 1001L, sessionId = 1L;

        ConversationBufferMemoryReader reader = ConversationBufferMemoryReader.builder()
                .userId(userId).sessionId(sessionId).storage(storage).build();
        ConversationBufferMemoryStorer storer = ConversationBufferMemoryStorer.builder()
                .userId(userId).sessionId(sessionId).storage(storage).build();

        FlowInstance chain = chainActor.builder()
                .next(buildPrompt()).next(reader).next(buildLlm()).next(new StrOutputParser()).next(storer)
                .build();

        ChatGeneration r1 = chainActor.invoke(chain, Map.of(
                "messages", BaseMessage.fromMessage(MessageType.HUMAN.getCode(), "Hi! I'm Todd. I come from Beijing.")));
        System.out.println("[Buffer] Turn1: " + r1);

        ChatGeneration r2 = chainActor.invoke(chain, Map.of(
                "messages", BaseMessage.fromMessage(MessageType.HUMAN.getCode(), "What's my name?")));
        System.out.println("[Buffer] Turn2: " + r2);

        ChatGeneration r3 = chainActor.invoke(chain, Map.of(
                "messages", BaseMessage.fromMessage(MessageType.HUMAN.getCode(), "Where do I come from?")));
        System.out.println("[Buffer] Turn3: " + r3);
    }

    /**
     * ConversationBufferWindowMemory: keeps at most `maxSize` turns in storage (oldest are dropped).
     * Reader injects the last `limit` turns.
     * With maxSize=2 and 3 turns sent, the first turn should be gone from storage.
     */
    @Test
    public void testBufferWindowMemory() {
        InMemoryConversationStorage storage = new InMemoryConversationStorage();
        long userId = 1001L, sessionId = 2L;

        ConversationBufferWindowMemoryReader reader = ConversationBufferWindowMemoryReader.builder()
                .userId(userId).sessionId(sessionId).storage(storage).build();
        ConversationBufferWindowMemoryStorer storer = ConversationBufferWindowMemoryStorer.builder()
                .userId(userId).sessionId(sessionId).maxSize(2).storage(storage).build();

        FlowInstance chain = chainActor.builder()
                .next(buildPrompt()).next(reader).next(buildLlm()).next(new StrOutputParser()).next(storer)
                .build();

        ChatGeneration r1 = chainActor.invoke(chain, Map.of(
                "messages", BaseMessage.fromMessage(MessageType.HUMAN.getCode(), "Hi! I'm Todd. I come from Beijing.")));
        System.out.println("[BufferWindow] Turn1: " + r1);

        ChatGeneration r2 = chainActor.invoke(chain, Map.of(
                "messages", BaseMessage.fromMessage(MessageType.HUMAN.getCode(), "My favorite color is blue.")));
        System.out.println("[BufferWindow] Turn2: " + r2);

        // After this turn storage has maxSize=2 turns; Turn1 is dropped
        ChatGeneration r3 = chainActor.invoke(chain, Map.of(
                "messages", BaseMessage.fromMessage(MessageType.HUMAN.getCode(), "What's my name? (should be forgotten due to window)")));
        System.out.println("[BufferWindow] Turn3 (name may be forgotten): " + r3);

        ChatGeneration r4 = chainActor.invoke(chain, Map.of(
                "messages", BaseMessage.fromMessage(MessageType.HUMAN.getCode(), "What's my favorite color?")));
        System.out.println("[BufferWindow] Turn4 (color should be remembered): " + r4);
    }

    /**
     * ConversationSummaryMemory: after every turn the LLM produces an updated rolling summary.
     * Storage always holds exactly one SUMMARY entry.
     * Verifies that information from early turns survives through the summary.
     */
    @Test
    public void testSummaryMemory() {
        InMemoryConversationStorage storage = new InMemoryConversationStorage();
        long userId = 1001L, sessionId = 3L;
        ChatAliyun llm = buildLlm();

        ConversationSummaryMemoryReader reader = ConversationSummaryMemoryReader.builder()
                .userId(userId).sessionId(sessionId).storage(storage).build();
        ConversationSummaryMemoryStorer storer = ConversationSummaryMemoryStorer.builder()
                .userId(userId).sessionId(sessionId).storage(storage).llm(llm).build();

        FlowInstance chain = chainActor.builder()
                .next(buildPrompt()).next(reader).next(llm).next(new StrOutputParser()).next(storer)
                .build();

        ChatGeneration r1 = chainActor.invoke(chain, Map.of(
                "messages", BaseMessage.fromMessage(MessageType.HUMAN.getCode(), "Hi! I'm Todd. I come from Beijing.")));
        System.out.println("[Summary] Turn1: " + r1);
        System.out.println("[Summary] Storage after Turn1: " + storage.loadAll(0L, userId, sessionId));

        ChatGeneration r2 = chainActor.invoke(chain, Map.of(
                "messages", BaseMessage.fromMessage(MessageType.HUMAN.getCode(), "My favorite food is Peking Duck.")));
        System.out.println("[Summary] Turn2: " + r2);
        System.out.println("[Summary] Storage after Turn2: " + storage.loadAll(0L, userId, sessionId));

        ChatGeneration r3 = chainActor.invoke(chain, Map.of(
                "messages", BaseMessage.fromMessage(MessageType.HUMAN.getCode(), "What do you know about me so far?")));
        System.out.println("[Summary] Turn3: " + r3);
    }

    /**
     * ConversationSummaryBufferMemory: keeps the last `maxSize` turns verbatim (buffer).
     * When buffer exceeds maxSize, the oldest turn is merged into a rolling summary via LLM.
     * The summary entry is NOT counted toward maxSize.
     * Verifies: early info survives in summary; recent turns are preserved verbatim.
     */
    @Test
    public void testSummaryBufferMemory() {
        InMemoryConversationStorage storage = new InMemoryConversationStorage();
        long userId = 1001L, sessionId = 4L;
        ChatAliyun llm = buildLlm();

        ConversationSummaryBufferMemoryReader reader = ConversationSummaryBufferMemoryReader.builder()
                .userId(userId).sessionId(sessionId).storage(storage).build();
        ConversationSummaryBufferMemoryStorer storer = ConversationSummaryBufferMemoryStorer.builder()
                .userId(userId).sessionId(sessionId).maxSize(2).storage(storage).llm(llm).build();

        FlowInstance chain = chainActor.builder()
                .next(buildPrompt()).next(reader).next(llm).next(new StrOutputParser()).next(storer)
                .build();

        ChatGeneration r1 = chainActor.invoke(chain, Map.of(
                "messages", BaseMessage.fromMessage(MessageType.HUMAN.getCode(), "Hi! I'm Todd. I come from Beijing.")));
        System.out.println("[SummaryBuffer] Turn1: " + r1);
        System.out.println("[SummaryBuffer] Storage: " + storage.loadAll(0L, userId, sessionId));

        ChatGeneration r2 = chainActor.invoke(chain, Map.of(
                "messages", BaseMessage.fromMessage(MessageType.HUMAN.getCode(), "My favorite color is blue.")));
        System.out.println("[SummaryBuffer] Turn2: " + r2);
        System.out.println("[SummaryBuffer] Storage: " + storage.loadAll(0L, userId, sessionId));

        // buffer(2) is full; adding Turn3 pushes Turn1 into the summary
        ChatGeneration r3 = chainActor.invoke(chain, Map.of(
                "messages", BaseMessage.fromMessage(MessageType.HUMAN.getCode(), "I work as a software engineer.")));
        System.out.println("[SummaryBuffer] Turn3: " + r3);
        System.out.println("[SummaryBuffer] Storage (Turn1 now in summary): " + storage.loadAll(0L, userId, sessionId));

        // Turn2 should also get compressed into summary now
        ChatGeneration r4 = chainActor.invoke(chain, Map.of(
                "messages", BaseMessage.fromMessage(MessageType.HUMAN.getCode(), "What do you know about me?")));
        System.out.println("[SummaryBuffer] Turn4: " + r4);
        System.out.println("[SummaryBuffer] Storage: " + storage.loadAll(0L, userId, sessionId));
    }
}
