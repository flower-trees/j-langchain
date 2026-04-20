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

import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.extern.slf4j.Slf4j;
import org.salt.jlangchain.core.history.HistoryInfos;
import org.salt.jlangchain.core.history.memory.ConversationMemoryReaderBase;
import org.salt.jlangchain.core.history.storage.ConversationStorage;

import java.util.ArrayList;
import java.util.List;

/**
 * Reads the most recent {@code limit} turns from storage.
 * Older turns beyond the window are simply ignored (not loaded).
 */
@Slf4j
@Data
@EqualsAndHashCode(callSuper = true)
public class ConversationBufferWindowMemoryReader extends ConversationMemoryReaderBase {

    @Override
    public List<HistoryInfos> readHistory() {
        List<HistoryInfos> all = storage.loadAll(appId, userId, sessionId);
        if (all.size() > limit) {
            return new ArrayList<>(all.subList(all.size() - limit, all.size()));
        }
        return all;
    }

    public static Builder builder() { return new Builder(); }

    public static final class Builder {
        private Long appId;
        private Long userId;
        private Long sessionId;
        private Integer limit;
        private ConversationStorage storage;

        public Builder appId(Long v)                 { this.appId = v;     return this; }
        public Builder userId(Long v)                { this.userId = v;    return this; }
        public Builder sessionId(Long v)             { this.sessionId = v; return this; }
        public Builder limit(Integer v)              { this.limit = v;     return this; }
        public Builder storage(ConversationStorage v){ this.storage = v;   return this; }

        public ConversationBufferWindowMemoryReader build() {
            ConversationBufferWindowMemoryReader r = new ConversationBufferWindowMemoryReader();
            if (appId != null)     r.setAppId(appId);
            if (userId != null)    r.setUserId(userId);
            if (sessionId != null) r.setSessionId(sessionId);
            if (limit != null)     r.setLimit(limit);
            if (storage != null)   r.setStorage(storage);
            return r;
        }
    }
}
