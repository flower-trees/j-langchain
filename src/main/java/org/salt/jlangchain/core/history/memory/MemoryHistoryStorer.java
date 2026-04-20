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

import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.extern.slf4j.Slf4j;
import org.salt.jlangchain.core.history.HistoryInfos;
import org.salt.jlangchain.core.history.HistoryStorerBase;

import java.util.List;

@Slf4j
@EqualsAndHashCode(callSuper = true)
@Data
public class MemoryHistoryStorer extends HistoryStorerBase {

    // appId / userId / sessionId are inherited from HistoryBase

    /** Maximum number of conversation turns to keep in memory per session. Oldest turns are dropped. */
    private Integer maxSize = 100;

    @Override
    public void storeHistory(HistoryInfos historyInfos) {
        List<HistoryInfos> list = MemoryHistory.getOrCreate(appId, userId, sessionId);
        synchronized (list) {
            list.add(historyInfos);
            if (list.size() > maxSize) {
                list.remove(0);
            }
        }
    }

    public static MemoryHistoryStorerBuilder builder() {
        return new MemoryHistoryStorerBuilder();
    }

    public static final class MemoryHistoryStorerBuilder {
        private Long appId;
        private Long userId;
        private Long sessionId;
        private Integer maxSize;

        private MemoryHistoryStorerBuilder() {
        }

        public MemoryHistoryStorerBuilder appId(Long appId) {
            this.appId = appId;
            return this;
        }

        public MemoryHistoryStorerBuilder userId(Long userId) {
            this.userId = userId;
            return this;
        }

        public MemoryHistoryStorerBuilder sessionId(Long sessionId) {
            this.sessionId = sessionId;
            return this;
        }

        public MemoryHistoryStorerBuilder maxSize(Integer maxSize) {
            this.maxSize = maxSize;
            return this;
        }

        public MemoryHistoryStorer build() {
            MemoryHistoryStorer storer = new MemoryHistoryStorer();
            if (appId != null)    storer.setAppId(appId);
            if (userId != null)   storer.setUserId(userId);
            if (sessionId != null) storer.setSessionId(sessionId);
            if (maxSize != null)  storer.setMaxSize(maxSize);
            return storer;
        }
    }
}
