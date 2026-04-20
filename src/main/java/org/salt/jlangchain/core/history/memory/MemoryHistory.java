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

import org.salt.jlangchain.core.history.HistoryInfos;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

public class MemoryHistory {

    // appId -> userId -> sessionId -> conversation turns
    private static final ConcurrentHashMap<String, ConcurrentHashMap<String, ConcurrentHashMap<String, List<HistoryInfos>>>> historyMap
            = new ConcurrentHashMap<>();

    /**
     * Returns the conversation-turn list for the given (appId, userId, sessionId),
     * creating it on first access. The returned list is thread-safe for individual
     * operations; compound operations (read-then-modify) must synchronize on the list.
     */
    protected static List<HistoryInfos> getOrCreate(Long appId, Long userId, Long sessionId) {
        return historyMap
                .computeIfAbsent(String.valueOf(appId),  k -> new ConcurrentHashMap<>())
                .computeIfAbsent(String.valueOf(userId),  k -> new ConcurrentHashMap<>())
                .computeIfAbsent(String.valueOf(sessionId), k -> Collections.synchronizedList(new ArrayList<>()));
    }
}
