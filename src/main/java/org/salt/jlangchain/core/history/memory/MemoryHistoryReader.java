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
import org.salt.jlangchain.core.history.HistoryReaderBase;

import java.util.ArrayList;
import java.util.List;

@Slf4j
@EqualsAndHashCode(callSuper = true)
@Data
public class MemoryHistoryReader extends HistoryReaderBase {

    // appId / userId / sessionId / limit are all inherited from HistoryBase / HistoryReaderBase

    @Override
    public List<HistoryInfos> readHistory() {
        List<HistoryInfos> list = MemoryHistory.getOrCreate(appId, userId, sessionId);
        synchronized (list) {
            if (list.size() > limit) {
                return new ArrayList<>(list.subList(list.size() - limit, list.size()));
            }
            return new ArrayList<>(list);
        }
    }

    public static MemoryHistoryReaderBuilder builder() {
        return new MemoryHistoryReaderBuilder();
    }

    public static final class MemoryHistoryReaderBuilder {
        private Long appId;
        private Long userId;
        private Long sessionId;
        private Integer limit;

        private MemoryHistoryReaderBuilder() {
        }

        public MemoryHistoryReaderBuilder appId(Long appId) {
            this.appId = appId;
            return this;
        }

        public MemoryHistoryReaderBuilder userId(Long userId) {
            this.userId = userId;
            return this;
        }

        public MemoryHistoryReaderBuilder sessionId(Long sessionId) {
            this.sessionId = sessionId;
            return this;
        }

        public MemoryHistoryReaderBuilder limit(Integer limit) {
            this.limit = limit;
            return this;
        }

        public MemoryHistoryReader build() {
            MemoryHistoryReader reader = new MemoryHistoryReader();
            if (appId != null)     reader.setAppId(appId);
            if (userId != null)    reader.setUserId(userId);
            if (sessionId != null) reader.setSessionId(sessionId);
            if (limit != null)     reader.setLimit(limit);
            return reader;
        }
    }
}
