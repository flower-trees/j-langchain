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

@Slf4j
@EqualsAndHashCode(callSuper = true)
@Data
public class MemoryHistoryStorer extends HistoryStorerBase {

    protected Long userId = 0L;
    protected Long sessionId = 0L;
    protected Integer limit = 10;

    public void storeHistory(HistoryInfos historyInfos) {

        MemoryHistory.init(userId, sessionId);

        MemoryHistory.historyMap.get(String.valueOf(userId)).get(String.valueOf(sessionId)).add(historyInfos);
    }

    public static MemoryHistoryStorerBuilder builder() {
        return new MemoryHistoryStorerBuilder();
    }

    public static final class MemoryHistoryStorerBuilder {
        private Long userId;
        private boolean userIdSet;
        private Long sessionId;
        private boolean sessionIdSet;
        private Integer limit;
        private boolean limitSet;

        private MemoryHistoryStorerBuilder() {
        }

        public MemoryHistoryStorerBuilder userId(Long userId) {
            this.userId = userId;
            this.userIdSet = true;
            return this;
        }

        public MemoryHistoryStorerBuilder sessionId(Long sessionId) {
            this.sessionId = sessionId;
            this.sessionIdSet = true;
            return this;
        }

        public MemoryHistoryStorerBuilder limit(Integer limit) {
            this.limit = limit;
            this.limitSet = true;
            return this;
        }

        public MemoryHistoryStorer build() {
            MemoryHistoryStorer storer = new MemoryHistoryStorer();
            storer.setUserId(this.userIdSet ? this.userId : 0L);
            storer.setSessionId(this.sessionIdSet ? this.sessionId : 0L);
            storer.setLimit(this.limitSet ? this.limit : 10);
            return storer;
        }

        @Override
        public String toString() {
            return "MemoryHistoryStorer.MemoryHistoryStorerBuilder(userId=" + (this.userIdSet ? this.userId : 0L) + ", sessionId=" + (this.sessionIdSet ? this.sessionId : 0L) + ", limit=" + (this.limitSet ? this.limit : 10) + ")";
        }
    }
}
