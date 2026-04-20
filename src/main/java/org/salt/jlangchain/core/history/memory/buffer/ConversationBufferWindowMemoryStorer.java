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
import org.salt.jlangchain.core.history.memory.ConversationMemoryStorerBase;
import org.salt.jlangchain.core.history.storage.ConversationStorage;

import java.util.ArrayList;
import java.util.List;

/**
 * Appends each new turn and drops the oldest when total turns exceed {@code maxSize}.
 * No LLM call required.
 */
@Slf4j
@Data
@EqualsAndHashCode(callSuper = true)
public class ConversationBufferWindowMemoryStorer extends ConversationMemoryStorerBase {

    @Override
    public void storeHistory(HistoryInfos historyInfos) {
        List<HistoryInfos> all = new ArrayList<>(storage.loadAll(appId, userId, sessionId));
        all.add(historyInfos);
        if (all.size() > maxSize) {
            all = new ArrayList<>(all.subList(all.size() - maxSize, all.size()));
        }
        storage.replace(appId, userId, sessionId, all);
    }

    public static Builder builder() { return new Builder(); }

    public static final class Builder {
        private Long appId;
        private Long userId;
        private Long sessionId;
        private Integer maxSize;
        private ConversationStorage storage;

        public Builder appId(Long v)                 { this.appId = v;     return this; }
        public Builder userId(Long v)                { this.userId = v;    return this; }
        public Builder sessionId(Long v)             { this.sessionId = v; return this; }
        public Builder maxSize(Integer v)            { this.maxSize = v;   return this; }
        public Builder storage(ConversationStorage v){ this.storage = v;   return this; }

        public ConversationBufferWindowMemoryStorer build() {
            ConversationBufferWindowMemoryStorer s = new ConversationBufferWindowMemoryStorer();
            if (appId != null)     s.setAppId(appId);
            if (userId != null)    s.setUserId(userId);
            if (sessionId != null) s.setSessionId(sessionId);
            if (maxSize != null)   s.setMaxSize(maxSize);
            if (storage != null)   s.setStorage(storage);
            return s;
        }
    }
}
