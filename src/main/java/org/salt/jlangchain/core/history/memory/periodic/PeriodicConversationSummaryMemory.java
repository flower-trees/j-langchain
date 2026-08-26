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

package org.salt.jlangchain.core.history.memory.periodic;

import org.salt.jlangchain.core.history.HistoryInfos;
import org.salt.jlangchain.core.history.memory.ConversationMemory;
import org.salt.jlangchain.core.history.storage.ConversationStorage;
import org.salt.jlangchain.core.llm.BaseChatModel;

import java.util.List;

/**
 * Combined periodic summary-buffer memory: keeps raw turns until {@code maxSize} is reached,
 * then compresses the whole accumulated batch into a rolling LLM summary in one call and clears
 * the buffer — see {@link PeriodicConversationSummaryMemoryStorer} for the exact trigger/merge
 * logic and how it compares to the rolling {@code ConversationSummaryBufferMemory}.
 * Delegates to {@link PeriodicConversationSummaryMemoryStorer} and {@link PeriodicConversationSummaryMemoryReader}.
 */
public class PeriodicConversationSummaryMemory implements ConversationMemory {

    private final PeriodicConversationSummaryMemoryStorer storer;
    private final PeriodicConversationSummaryMemoryReader reader;

    private PeriodicConversationSummaryMemory(PeriodicConversationSummaryMemoryStorer storer,
                                              PeriodicConversationSummaryMemoryReader reader) {
        this.storer = storer;
        this.reader = reader;
    }

    @Override
    public void storeHistory(HistoryInfos historyInfos) {
        storer.storeHistory(historyInfos);
    }

    @Override
    public List<HistoryInfos> readHistory() {
        return reader.readHistory();
    }

    public static Builder builder() { return new Builder(); }

    public static final class Builder {
        private Long appId;
        private Long userId;
        private Long sessionId;
        private Integer maxSize;
        private ConversationStorage storage;
        private BaseChatModel llm;

        public Builder appId(Long v)                 { this.appId = v;     return this; }
        public Builder userId(Long v)                { this.userId = v;    return this; }
        public Builder sessionId(Long v)             { this.sessionId = v; return this; }
        public Builder maxSize(Integer v)            { this.maxSize = v;   return this; }
        public Builder storage(ConversationStorage v){ this.storage = v;   return this; }
        public Builder llm(BaseChatModel v)          { this.llm = v;       return this; }

        public PeriodicConversationSummaryMemory build() {
            PeriodicConversationSummaryMemoryStorer s = PeriodicConversationSummaryMemoryStorer.builder()
                    .appId(appId).userId(userId).sessionId(sessionId)
                    .maxSize(maxSize).storage(storage).llm(llm).build();
            PeriodicConversationSummaryMemoryReader r = PeriodicConversationSummaryMemoryReader.builder()
                    .appId(appId).userId(userId).sessionId(sessionId)
                    .storage(storage).build();
            return new PeriodicConversationSummaryMemory(s, r);
        }
    }
}
