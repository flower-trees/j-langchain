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

package org.salt.jlangchain.core.history.memory.buffer;

import org.salt.jlangchain.core.history.HistoryInfos;
import org.salt.jlangchain.core.history.memory.ConversationMemory;
import org.salt.jlangchain.core.history.storage.ConversationStorage;

import java.util.List;

/**
 * Combined buffer memory: appends every turn with no size limit.
 * Delegates to {@link ConversationBufferMemoryStorer} and {@link ConversationBufferMemoryReader}.
 */
public class ConversationBufferMemory implements ConversationMemory {

    private final ConversationBufferMemoryStorer storer;
    private final ConversationBufferMemoryReader reader;

    private ConversationBufferMemory(ConversationBufferMemoryStorer storer,
                                     ConversationBufferMemoryReader reader) {
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
        private Integer limit;
        private ConversationStorage storage;

        public Builder appId(Long v)                  { this.appId = v;     return this; }
        public Builder userId(Long v)                 { this.userId = v;    return this; }
        public Builder sessionId(Long v)              { this.sessionId = v; return this; }
        public Builder limit(Integer v)               { this.limit = v;     return this; }
        public Builder storage(ConversationStorage v) { this.storage = v;   return this; }

        public ConversationBufferMemory build() {
            ConversationBufferMemoryStorer s = ConversationBufferMemoryStorer.builder()
                    .appId(appId).userId(userId).sessionId(sessionId).storage(storage).build();
            ConversationBufferMemoryReader r = ConversationBufferMemoryReader.builder()
                    .appId(appId).userId(userId).sessionId(sessionId)
                    .limit(limit).storage(storage).build();
            return new ConversationBufferMemory(s, r);
        }
    }
}
