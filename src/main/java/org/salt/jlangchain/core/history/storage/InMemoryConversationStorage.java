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

package org.salt.jlangchain.core.history.storage;

import org.salt.jlangchain.core.history.HistoryInfos;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory implementation of {@link ConversationStorage}.
 * Data is stored per (appId → userId → sessionId) and lives for the JVM lifetime.
 * Swap this bean for a MySQL / Redis / PostgreSQL implementation without changing any Memory class.
 */
@Component
public class InMemoryConversationStorage implements ConversationStorage {

    // appId -> userId -> sessionId -> turns
    private final ConcurrentHashMap<String, ConcurrentHashMap<String, ConcurrentHashMap<String, List<HistoryInfos>>>> store
            = new ConcurrentHashMap<>();

    private List<HistoryInfos> getOrCreate(Long appId, Long userId, Long sessionId) {
        return store
                .computeIfAbsent(String.valueOf(appId),    k -> new ConcurrentHashMap<>())
                .computeIfAbsent(String.valueOf(userId),   k -> new ConcurrentHashMap<>())
                .computeIfAbsent(String.valueOf(sessionId), k -> Collections.synchronizedList(new ArrayList<>()));
    }

    @Override
    public List<HistoryInfos> loadAll(Long appId, Long userId, Long sessionId) {
        List<HistoryInfos> list = getOrCreate(appId, userId, sessionId);
        synchronized (list) {
            return new ArrayList<>(list);
        }
    }

    @Override
    public void append(Long appId, Long userId, Long sessionId, HistoryInfos turn) {
        List<HistoryInfos> list = getOrCreate(appId, userId, sessionId);
        synchronized (list) {
            list.add(turn);
        }
    }

    @Override
    public void replace(Long appId, Long userId, Long sessionId, List<HistoryInfos> compacted) {
        List<HistoryInfos> list = getOrCreate(appId, userId, sessionId);
        synchronized (list) {
            list.clear();
            list.addAll(compacted);
        }
    }

    @Override
    public void clear(Long appId, Long userId, Long sessionId) {
        List<HistoryInfos> list = getOrCreate(appId, userId, sessionId);
        synchronized (list) {
            list.clear();
        }
    }
}
