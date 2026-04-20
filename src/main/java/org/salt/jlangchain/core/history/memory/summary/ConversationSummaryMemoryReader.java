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

import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.extern.slf4j.Slf4j;
import org.salt.jlangchain.core.history.HistoryInfos;
import org.salt.jlangchain.core.history.memory.ConversationMemoryReaderBase;
import org.salt.jlangchain.core.history.storage.ConversationStorage;

import java.util.List;

/**
 * Returns the stored history as-is (always just one SUMMARY entry after the first turn).
 * The summary is injected as a SystemMessage by {@link org.salt.jlangchain.core.history.HistoryReaderBase}.
 */
@Slf4j
@Data
@EqualsAndHashCode(callSuper = true)
public class ConversationSummaryMemoryReader extends ConversationMemoryReaderBase {

    @Override
    public List<HistoryInfos> readHistory() {
        return storage.loadAll(appId, userId, sessionId);
    }

    public static Builder builder() { return new Builder(); }

    public static final class Builder {
        private Long appId;
        private Long userId;
        private Long sessionId;
        private ConversationStorage storage;

        public Builder appId(Long v)                 { this.appId = v;     return this; }
        public Builder userId(Long v)                { this.userId = v;    return this; }
        public Builder sessionId(Long v)             { this.sessionId = v; return this; }
        public Builder storage(ConversationStorage v){ this.storage = v;   return this; }

        public ConversationSummaryMemoryReader build() {
            ConversationSummaryMemoryReader r = new ConversationSummaryMemoryReader();
            if (appId != null)     r.setAppId(appId);
            if (userId != null)    r.setUserId(userId);
            if (sessionId != null) r.setSessionId(sessionId);
            if (storage != null)   r.setStorage(storage);
            return r;
        }
    }
}
