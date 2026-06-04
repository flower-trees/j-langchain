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

package org.salt.jlangchain.core.history.memory.summary;

import org.salt.jlangchain.core.history.HistoryInfos;
import org.salt.jlangchain.core.history.memory.ConversationMemory;
import org.salt.jlangchain.core.history.storage.ConversationStorage;
import org.salt.jlangchain.core.llm.BaseChatModel;

import java.util.List;

/**
 * Combined summary memory: every turn is folded into a rolling LLM-generated summary.
 * Delegates to {@link ConversationSummaryMemoryStorer} and {@link ConversationSummaryMemoryReader}.
 */
public class ConversationSummaryMemory implements ConversationMemory {

    private final ConversationSummaryMemoryStorer storer;
    private final ConversationSummaryMemoryReader reader;

    private ConversationSummaryMemory(ConversationSummaryMemoryStorer storer,
                                      ConversationSummaryMemoryReader reader) {
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
        private ConversationStorage storage;
        private BaseChatModel llm;

        public Builder appId(Long v)                 { this.appId = v;     return this; }
        public Builder userId(Long v)                { this.userId = v;    return this; }
        public Builder sessionId(Long v)             { this.sessionId = v; return this; }
        public Builder storage(ConversationStorage v){ this.storage = v;   return this; }
        public Builder llm(BaseChatModel v)          { this.llm = v;       return this; }

        public ConversationSummaryMemory build() {
            ConversationSummaryMemoryStorer s = ConversationSummaryMemoryStorer.builder()
                    .appId(appId).userId(userId).sessionId(sessionId)
                    .storage(storage).llm(llm).build();
            ConversationSummaryMemoryReader r = ConversationSummaryMemoryReader.builder()
                    .appId(appId).userId(userId).sessionId(sessionId)
                    .storage(storage).build();
            return new ConversationSummaryMemory(s, r);
        }
    }
}
