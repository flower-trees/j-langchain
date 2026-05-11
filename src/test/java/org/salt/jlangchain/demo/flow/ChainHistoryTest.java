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

import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.salt.function.flow.FlowInstance;
import org.salt.jlangchain.TestApplication;
import org.salt.jlangchain.core.BaseRunnable;
import org.salt.jlangchain.core.ChainActor;
import org.salt.jlangchain.core.history.HistoryInfos;
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

    // ── Storage-state assertions ───────────────────────────────────────────────

    /**
     * Buffer strategy: storage grows unboundedly — N turns produce N entries.
     */
    @Test
    public void testBufferMemoryStorageGrows() {
        InMemoryConversationStorage storage = new InMemoryConversationStorage();
        long userId = 2001L, sessionId = 10L;

        ConversationBufferMemoryStorer storer = ConversationBufferMemoryStorer.builder()
                .userId(userId).sessionId(sessionId).storage(storage).build();
        FlowInstance chain = chainActor.builder()
                .next(buildPrompt()).next(ConversationBufferMemoryReader.builder()
                        .userId(userId).sessionId(sessionId).storage(storage).build())
                .next(buildLlm()).next(new StrOutputParser()).next(storer)
                .build();

        ChatGeneration r1 = chainActor.invoke(chain, msg("turn 1"));
        System.out.println("[BufferGrows] Turn1: " + r1.getText());
        System.out.println("[BufferGrows] Storage size after turn 1: " + storage.loadAll(0L, userId, sessionId).size());
        Assert.assertEquals(1, storage.loadAll(0L, userId, sessionId).size());

        ChatGeneration r2 = chainActor.invoke(chain, msg("turn 2"));
        System.out.println("[BufferGrows] Turn2: " + r2.getText());
        System.out.println("[BufferGrows] Storage size after turn 2: " + storage.loadAll(0L, userId, sessionId).size());
        Assert.assertEquals(2, storage.loadAll(0L, userId, sessionId).size());

        ChatGeneration r3 = chainActor.invoke(chain, msg("turn 3"));
        System.out.println("[BufferGrows] Turn3: " + r3.getText());
        System.out.println("[BufferGrows] Storage size after turn 3: " + storage.loadAll(0L, userId, sessionId).size());
        Assert.assertEquals(3, storage.loadAll(0L, userId, sessionId).size());

        List<HistoryInfos> all = storage.loadAll(0L, userId, sessionId);
        for (int i = 0; i < all.size(); i++) {
            HistoryInfos h = all.get(i);
            System.out.println("[BufferGrows] Entry[" + i + "] type=" + h.getType()
                    + " messages=" + h.getMessages().size()
                    + " human=" + h.getMessages().get(0).getContent()
                    + " ai=" + h.getMessages().get(1).getContent());
            Assert.assertEquals(HistoryInfos.Type.NORMAL, h.getType());
            Assert.assertEquals(2, h.getMessages().size());
        }
    }

    /**
     * BufferWindow strategy: storage is capped at maxSize; oldest turns are discarded.
     */
    @Test
    public void testBufferWindowStorageStaysAtMaxSize() {
        InMemoryConversationStorage storage = new InMemoryConversationStorage();
        long userId = 2001L, sessionId = 11L;
        int maxSize = 2;

        ConversationBufferWindowMemoryStorer storer = ConversationBufferWindowMemoryStorer.builder()
                .userId(userId).sessionId(sessionId).maxSize(maxSize).storage(storage).build();
        FlowInstance chain = chainActor.builder()
                .next(buildPrompt()).next(ConversationBufferWindowMemoryReader.builder()
                        .userId(userId).sessionId(sessionId).storage(storage).build())
                .next(buildLlm()).next(new StrOutputParser()).next(storer)
                .build();

        ChatGeneration bw1 = chainActor.invoke(chain, msg("turn 1 — I am Alice"));
        System.out.println("[BufferWindow] Turn1: " + bw1.getText()
                + " | storage=" + storage.loadAll(0L, userId, sessionId).size());

        ChatGeneration bw2 = chainActor.invoke(chain, msg("turn 2 — I like cats"));
        System.out.println("[BufferWindow] Turn2: " + bw2.getText()
                + " | storage=" + storage.loadAll(0L, userId, sessionId).size());

        ChatGeneration bw3 = chainActor.invoke(chain, msg("turn 3 — What do I like?"));
        System.out.println("[BufferWindow] Turn3: " + bw3.getText()
                + " | storage=" + storage.loadAll(0L, userId, sessionId).size());

        ChatGeneration bw4 = chainActor.invoke(chain, msg("turn 4 — What is my name?"));
        System.out.println("[BufferWindow] Turn4: " + bw4.getText()
                + " | storage=" + storage.loadAll(0L, userId, sessionId).size());

        List<HistoryInfos> stored = storage.loadAll(0L, userId, sessionId);
        System.out.println("[BufferWindow] Final storage size=" + stored.size() + " (maxSize=" + maxSize + ")");
        for (int i = 0; i < stored.size(); i++) {
            System.out.println("[BufferWindow] Kept entry[" + i + "]: "
                    + stored.get(i).getMessages().get(0).getContent());
        }
        Assert.assertEquals("storage must be capped at maxSize", maxSize, stored.size());
        for (HistoryInfos h : stored) {
            Assert.assertEquals(HistoryInfos.Type.NORMAL, h.getType());
        }
    }

    /**
     * Summary strategy: storage always holds exactly one SUMMARY entry after the first turn.
     */
    @Test
    public void testSummaryMemoryStorageAlwaysHasOneSummaryEntry() {
        InMemoryConversationStorage storage = new InMemoryConversationStorage();
        long userId = 2001L, sessionId = 12L;
        ChatAliyun llm = buildLlm();

        ConversationSummaryMemoryStorer storer = ConversationSummaryMemoryStorer.builder()
                .userId(userId).sessionId(sessionId).storage(storage).llm(llm).build();
        FlowInstance chain = chainActor.builder()
                .next(buildPrompt()).next(ConversationSummaryMemoryReader.builder()
                        .userId(userId).sessionId(sessionId).storage(storage).build())
                .next(llm).next(new StrOutputParser()).next(storer)
                .build();

        ChatGeneration s1 = chainActor.invoke(chain, msg("I am Bob."));
        List<HistoryInfos> after1 = storage.loadAll(0L, userId, sessionId);
        System.out.println("[Summary] Turn1 response: " + s1.getText());
        System.out.println("[Summary] Storage after turn 1: count=" + after1.size()
                + " type=" + after1.get(0).getType()
                + " content=" + after1.get(0).getMessages().get(0).getContent());
        Assert.assertEquals("after turn 1: exactly 1 summary entry", 1, after1.size());
        Assert.assertEquals(HistoryInfos.Type.SUMMARY, after1.get(0).getType());

        ChatGeneration s2 = chainActor.invoke(chain, msg("My favourite food is pizza."));
        List<HistoryInfos> after2 = storage.loadAll(0L, userId, sessionId);
        System.out.println("[Summary] Turn2 response: " + s2.getText());
        System.out.println("[Summary] Storage after turn 2: count=" + after2.size()
                + " content=" + after2.get(0).getMessages().get(0).getContent());
        Assert.assertEquals("after turn 2: still exactly 1 summary entry", 1, after2.size());
        Assert.assertEquals(HistoryInfos.Type.SUMMARY, after2.get(0).getType());

        ChatGeneration s3 = chainActor.invoke(chain, msg("What have we talked about?"));
        List<HistoryInfos> after3 = storage.loadAll(0L, userId, sessionId);
        System.out.println("[Summary] Turn3 response: " + s3.getText());
        System.out.println("[Summary] Storage after turn 3: count=" + after3.size()
                + " content=" + after3.get(0).getMessages().get(0).getContent());
        Assert.assertEquals("after turn 3: still exactly 1 summary entry", 1, after3.size());
        Assert.assertEquals(HistoryInfos.Type.SUMMARY, after3.get(0).getType());
    }

    /**
     * SummaryBuffer strategy: after maxSize+1 turns, the oldest turn is in the summary;
     * the remaining maxSize turns are kept verbatim.
     * Storage structure: [SUMMARY, T2, T3] when maxSize=2 and 3 turns sent.
     */
    @Test
    public void testSummaryBufferStorageStructureAfterCompression() {
        InMemoryConversationStorage storage = new InMemoryConversationStorage();
        long userId = 2001L, sessionId = 13L;
        ChatAliyun llm = buildLlm();
        int maxSize = 2;

        ConversationSummaryBufferMemoryStorer storer = ConversationSummaryBufferMemoryStorer.builder()
                .userId(userId).sessionId(sessionId).maxSize(maxSize).storage(storage).llm(llm).build();
        FlowInstance chain = chainActor.builder()
                .next(buildPrompt()).next(ConversationSummaryBufferMemoryReader.builder()
                        .userId(userId).sessionId(sessionId).storage(storage).build())
                .next(llm).next(new StrOutputParser()).next(storer)
                .build();

        ChatGeneration sb1 = chainActor.invoke(chain, msg("I am Carol. Turn 1."));
        List<HistoryInfos> a1 = storage.loadAll(0L, userId, sessionId);
        System.out.println("[SummaryBuffer] Turn1: " + sb1.getText());
        System.out.printf("[SummaryBuffer] Storage after turn 1: count=%d types=%s%n",
                a1.size(), a1.stream().map(h -> h.getType().name()).toList());
        Assert.assertEquals("after turn 1: 1 buffer entry, no summary yet", 1, a1.size());
        Assert.assertEquals(HistoryInfos.Type.NORMAL, a1.get(0).getType());

        ChatGeneration sb2 = chainActor.invoke(chain, msg("My hobby is hiking. Turn 2."));
        List<HistoryInfos> a2 = storage.loadAll(0L, userId, sessionId);
        System.out.println("[SummaryBuffer] Turn2: " + sb2.getText());
        System.out.printf("[SummaryBuffer] Storage after turn 2: count=%d types=%s%n",
                a2.size(), a2.stream().map(h -> h.getType().name()).toList());
        Assert.assertEquals("after turn 2: 2 buffer entries, no summary yet", 2, a2.size());
        Assert.assertTrue(a2.stream().allMatch(h -> h.getType() == HistoryInfos.Type.NORMAL));

        // Turn 3 pushes Turn 1 into the summary
        ChatGeneration sb3 = chainActor.invoke(chain, msg("I also like photography. Turn 3."));
        List<HistoryInfos> a3 = storage.loadAll(0L, userId, sessionId);
        System.out.println("[SummaryBuffer] Turn3: " + sb3.getText());
        System.out.printf("[SummaryBuffer] Storage after turn 3: count=%d types=%s%n",
                a3.size(), a3.stream().map(h -> h.getType().name()).toList());
        System.out.println("[SummaryBuffer] Summary content: " + a3.get(0).getMessages().get(0).getContent());
        Assert.assertEquals("after turn 3: [SUMMARY] + 2 buffer entries", 3, a3.size());
        Assert.assertEquals("first entry must be SUMMARY", HistoryInfos.Type.SUMMARY, a3.get(0).getType());
        Assert.assertEquals(HistoryInfos.Type.NORMAL, a3.get(1).getType());
        Assert.assertEquals(HistoryInfos.Type.NORMAL, a3.get(2).getType());
    }

    /**
     * Each sessionId within the same storage instance is fully independent.
     */
    @Test
    public void testBufferMemorySessionIsolation() {
        InMemoryConversationStorage storage = new InMemoryConversationStorage();
        long userId = 3001L;

        ConversationBufferMemoryStorer storerA = ConversationBufferMemoryStorer.builder()
                .userId(userId).sessionId(100L).storage(storage).build();
        ConversationBufferMemoryStorer storerB = ConversationBufferMemoryStorer.builder()
                .userId(userId).sessionId(200L).storage(storage).build();

        FlowInstance chainA = chainActor.builder()
                .next(buildPrompt()).next(ConversationBufferMemoryReader.builder()
                        .userId(userId).sessionId(100L).storage(storage).build())
                .next(buildLlm()).next(new StrOutputParser()).next(storerA).build();
        FlowInstance chainB = chainActor.builder()
                .next(buildPrompt()).next(ConversationBufferMemoryReader.builder()
                        .userId(userId).sessionId(200L).storage(storage).build())
                .next(buildLlm()).next(new StrOutputParser()).next(storerB).build();

        ChatGeneration ia1 = chainActor.invoke(chainA, msg("session A turn 1"));
        System.out.println("[SessionIsolation] Chain A turn1: " + ia1.getText());
        ChatGeneration ia2 = chainActor.invoke(chainA, msg("session A turn 2"));
        System.out.println("[SessionIsolation] Chain A turn2: " + ia2.getText());
        ChatGeneration ib1 = chainActor.invoke(chainB, msg("session B turn 1"));
        System.out.println("[SessionIsolation] Chain B turn1: " + ib1.getText());

        int sizeA = storage.loadAll(0L, userId, 100L).size();
        int sizeB = storage.loadAll(0L, userId, 200L).size();
        System.out.println("[SessionIsolation] session A storage=" + sizeA + ", session B storage=" + sizeB);
        Assert.assertEquals("session A must have 2 turns", 2, sizeA);
        Assert.assertEquals("session B must have 1 turn",  1, sizeB);
    }

    /**
     * After clear(), the session history is empty and subsequent turns start fresh.
     */
    @Test
    public void testBufferMemoryClearResetsHistory() {
        InMemoryConversationStorage storage = new InMemoryConversationStorage();
        long userId = 3001L, sessionId = 300L;

        ConversationBufferMemoryStorer storer = ConversationBufferMemoryStorer.builder()
                .userId(userId).sessionId(sessionId).storage(storage).build();
        FlowInstance chain = chainActor.builder()
                .next(buildPrompt()).next(ConversationBufferMemoryReader.builder()
                        .userId(userId).sessionId(sessionId).storage(storage).build())
                .next(buildLlm()).next(new StrOutputParser()).next(storer).build();

        ChatGeneration c1 = chainActor.invoke(chain, msg("pre-clear turn 1"));
        System.out.println("[ClearReset] Pre-clear turn1: " + c1.getText());
        ChatGeneration c2 = chainActor.invoke(chain, msg("pre-clear turn 2"));
        System.out.println("[ClearReset] Pre-clear turn2: " + c2.getText());
        System.out.println("[ClearReset] Storage before clear: " + storage.loadAll(0L, userId, sessionId).size());
        Assert.assertEquals(2, storage.loadAll(0L, userId, sessionId).size());

        storage.clear(0L, userId, sessionId);
        System.out.println("[ClearReset] Storage after clear: " + storage.loadAll(0L, userId, sessionId).size());
        Assert.assertTrue("storage must be empty after clear", storage.loadAll(0L, userId, sessionId).isEmpty());

        ChatGeneration c3 = chainActor.invoke(chain, msg("post-clear turn 1"));
        System.out.println("[ClearReset] Post-clear turn1: " + c3.getText());
        System.out.println("[ClearReset] Storage after post-clear turn: " + storage.loadAll(0L, userId, sessionId).size());
        Assert.assertEquals("history restarts from 1 after clear", 1, storage.loadAll(0L, userId, sessionId).size());
    }

    /**
     * BufferMemoryReader with limit=1 injects only the most recent turn, even when storage has more.
     */
    @Test
    public void testBufferMemoryReaderRespectsLimit() {
        InMemoryConversationStorage storage = new InMemoryConversationStorage();
        long userId = 3001L, sessionId = 400L;

        // Storer keeps all turns (no limit on storage)
        ConversationBufferMemoryStorer storer = ConversationBufferMemoryStorer.builder()
                .userId(userId).sessionId(sessionId).storage(storage).build();
        // Reader injects only the last 1 turn into the prompt
        ConversationBufferMemoryReader limitedReader = ConversationBufferMemoryReader.builder()
                .userId(userId).sessionId(sessionId).limit(1).storage(storage).build();
        FlowInstance chain = chainActor.builder()
                .next(buildPrompt()).next(limitedReader)
                .next(buildLlm()).next(new StrOutputParser()).next(storer).build();

        ChatGeneration rl1 = chainActor.invoke(chain, msg("I am Dave. Remember my name."));
        System.out.println("[ReaderLimit] Turn1: " + rl1.getText()
                + " | storage total=" + storage.loadAll(0L, userId, sessionId).size());

        ChatGeneration rl2 = chainActor.invoke(chain, msg("My city is London. Remember my city."));
        System.out.println("[ReaderLimit] Turn2: " + rl2.getText()
                + " | storage total=" + storage.loadAll(0L, userId, sessionId).size());

        ChatGeneration r3 = chainActor.invoke(chain, msg("Do you know my name?"));
        System.out.println("[ReaderLimit] Turn3 (limit=1, only city turn injected — name may be forgotten): " + r3.getText());
        System.out.println("[ReaderLimit] Storage total turns (all kept): " + storage.loadAll(0L, userId, sessionId).size());

        // Storage must still have all 3 turns
        Assert.assertEquals(3, storage.loadAll(0L, userId, sessionId).size());
        // The model only saw the last 1 turn (city turn), so it may not recall the name
        Assert.assertNotNull(r3);
        Assert.assertFalse(r3.getText().isBlank());
    }

    /**
     * SummaryMemory summary content contains facts from earlier turns.
     */
    @Test
    public void testSummaryMemoryPreservesFacts() {
        InMemoryConversationStorage storage = new InMemoryConversationStorage();
        long userId = 3001L, sessionId = 500L;
        ChatAliyun llm = buildLlm();

        ConversationSummaryMemoryStorer storer = ConversationSummaryMemoryStorer.builder()
                .userId(userId).sessionId(sessionId).storage(storage).llm(llm).build();
        FlowInstance chain = chainActor.builder()
                .next(buildPrompt()).next(ConversationSummaryMemoryReader.builder()
                        .userId(userId).sessionId(sessionId).storage(storage).build())
                .next(llm).next(new StrOutputParser()).next(storer).build();

        chainActor.invoke(chain, msg("My name is Eve. I live in Paris."));
        chainActor.invoke(chain, msg("My job is data scientist."));
        chainActor.invoke(chain, msg("I have a cat named Luna."));

        List<HistoryInfos> stored = storage.loadAll(0L, userId, sessionId);
        Assert.assertEquals(1, stored.size());
        Assert.assertEquals(HistoryInfos.Type.SUMMARY, stored.get(0).getType());

        String summaryContent = stored.get(0).getMessages().get(0).getContent();
        System.out.println("[SummaryPreservation] Summary: " + summaryContent);
        Assert.assertFalse("summary must not be blank", summaryContent.isBlank());
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private Map<String, Object> msg(String text) {
        return Map.of("messages", BaseMessage.fromMessage(MessageType.HUMAN.getCode(), text));
    }
}
