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

package org.salt.jlangchain.core.history.memory.bufferwindow;

import org.salt.jlangchain.core.history.HistoryInfos;
import org.salt.jlangchain.core.history.memory.ConversationMemory;
import org.salt.jlangchain.core.history.storage.ConversationStorage;

import java.util.List;

/**
 * Combined sliding-window memory: keeps the most recent {@code maxSize} turns.
 * Delegates to {@link ConversationBufferWindowMemoryStorer} and {@link ConversationBufferWindowMemoryReader}.
 */
public class ConversationBufferWindowMemory implements ConversationMemory {

    private final ConversationBufferWindowMemoryStorer storer;
    private final ConversationBufferWindowMemoryReader reader;

    private ConversationBufferWindowMemory(ConversationBufferWindowMemoryStorer storer,
                                           ConversationBufferWindowMemoryReader reader) {
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
        private Integer limit;
        private ConversationStorage storage;

        public Builder appId(Long v)                  { this.appId = v;     return this; }
        public Builder userId(Long v)                 { this.userId = v;    return this; }
        public Builder sessionId(Long v)              { this.sessionId = v; return this; }
        public Builder maxSize(Integer v)             { this.maxSize = v;   return this; }
        public Builder limit(Integer v)               { this.limit = v;     return this; }
        public Builder storage(ConversationStorage v) { this.storage = v;   return this; }

        public ConversationBufferWindowMemory build() {
            ConversationBufferWindowMemoryStorer s = ConversationBufferWindowMemoryStorer.builder()
                    .appId(appId).userId(userId).sessionId(sessionId)
                    .maxSize(maxSize).storage(storage).build();
            ConversationBufferWindowMemoryReader r = ConversationBufferWindowMemoryReader.builder()
                    .appId(appId).userId(userId).sessionId(sessionId)
                    .limit(limit).storage(storage).build();
            return new ConversationBufferWindowMemory(s, r);
        }
    }
}
