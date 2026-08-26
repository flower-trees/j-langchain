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

package org.salt.jlangchain.core.history.memory;

import org.junit.Assert;
import org.junit.Test;
import org.salt.jlangchain.ai.chat.strategy.AiChatActuator;
import org.salt.jlangchain.ai.common.param.AiChatInput;
import org.salt.jlangchain.core.history.HistoryInfos;
import org.salt.jlangchain.core.history.memory.periodic.PeriodicConversationSummaryMemory;
import org.salt.jlangchain.core.history.memory.summarybuffer.ConversationSummaryBufferMemory;
import org.salt.jlangchain.core.history.storage.InMemoryConversationStorage;
import org.salt.jlangchain.core.llm.BaseChatModel;
import org.salt.jlangchain.core.message.AIMessage;
import org.salt.jlangchain.core.message.HumanMessage;

import java.util.List;

/**
 * Compares LLM call counts between the rolling ({@code ConversationSummaryBufferMemory}) and
 * periodic ({@code PeriodicConversationSummaryMemory}) compaction strategies over the same
 * simulated long session.
 */
public class PeriodicVsRollingCompactionUnitTest {

    @Test
    public void periodicTriggersFarFewerSummaryCallsThanRollingOverLongSession() {
        int maxSize = 20;
        int totalTurns = 50;

        CountingChatModel rollingLlm = new CountingChatModel();
        ConversationSummaryBufferMemory rolling = ConversationSummaryBufferMemory.builder()
                .appId(0L).userId(0L).sessionId(1L)
                .maxSize(maxSize).storage(new InMemoryConversationStorage()).llm(rollingLlm).build();

        CountingChatModel periodicLlm = new CountingChatModel();
        PeriodicConversationSummaryMemory periodic = PeriodicConversationSummaryMemory.builder()
                .appId(0L).userId(0L).sessionId(2L)
                .maxSize(maxSize).storage(new InMemoryConversationStorage()).llm(periodicLlm).build();

        for (int i = 1; i <= totalTurns; i++) {
            rolling.storeHistory(turn(i));
            periodic.storeHistory(turn(i));
        }

        // Rolling: compacts once the buffer exceeds maxSize, i.e. every turn from (maxSize+1)
        // onward -> totalTurns - maxSize compactions (30 for 50 turns @ maxSize=20).
        Assert.assertEquals(totalTurns - maxSize, rollingLlm.callCount);

        // Periodic: compacts only when the buffer reaches maxSize, wiping it each time ->
        // floor(totalTurns / maxSize) compactions (2 for 50 turns @ maxSize=20), with the
        // remaining 10 turns left uncompacted in the buffer.
        Assert.assertEquals(totalTurns / maxSize, periodicLlm.callCount);

        // The actual point of this test: periodic must be dramatically cheaper, not just different.
        Assert.assertTrue("periodic must trigger far fewer summary LLM calls than rolling",
                periodicLlm.callCount < rollingLlm.callCount / 5);
    }

    @Test
    public void periodicLeavesUncompactedTailUntilThresholdReached() {
        int maxSize = 20;
        CountingChatModel llm = new CountingChatModel();
        InMemoryConversationStorage storage = new InMemoryConversationStorage();
        PeriodicConversationSummaryMemory memory = PeriodicConversationSummaryMemory.builder()
                .appId(0L).userId(0L).sessionId(3L)
                .maxSize(maxSize).storage(storage).llm(llm).build();

        for (int i = 1; i <= maxSize - 1; i++) {
            memory.storeHistory(turn(i));
        }

        Assert.assertEquals("must not compact before the threshold is reached", 0, llm.callCount);
        List<HistoryInfos> history = memory.readHistory();
        Assert.assertEquals(maxSize - 1, history.size());
        Assert.assertTrue("no entry should be a SUMMARY yet",
                history.stream().noneMatch(h -> h.getType() == HistoryInfos.Type.SUMMARY));
    }

    private HistoryInfos turn(int i) {
        return HistoryInfos.builder()
                .type(HistoryInfos.Type.NORMAL)
                .messages(List.of(
                        HumanMessage.builder().content("question " + i).build(),
                        AIMessage.builder().content("answer " + i).build()
                ))
                .build();
    }

    /** Bypasses BaseChatModel's Spring/actuator plumbing entirely — pure call counter. */
    private static class CountingChatModel extends BaseChatModel {
        int callCount = 0;

        @Override
        public AIMessage invoke(Object input) {
            callCount++;
            return AIMessage.builder().content("stub summary #" + callCount).build();
        }

        @Override
        public void otherInformation(AiChatInput aiChatInput) {}

        @Override
        public Class<? extends AiChatActuator> getActuator() {
            return null;
        }

        @Override
        public BaseChatModel copy() {
            return this;
        }
    }
}
