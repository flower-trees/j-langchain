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
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory implementation of {@link AgentTaskStorage}.
 * Data lives for the JVM lifetime.
 * Swap this bean for a persistent implementation without changing any agent executor.
 */
@Component
public class InMemoryAgentTaskStorage implements AgentTaskStorage {

    // taskId -> steps
    private final ConcurrentHashMap<String, List<HistoryInfos>> store = new ConcurrentHashMap<>();

    private List<HistoryInfos> getOrCreate(String taskId) {
        return store.computeIfAbsent(taskId, k -> Collections.synchronizedList(new ArrayList<>()));
    }

    @Override
    public void append(String taskId, HistoryInfos step) {
        List<HistoryInfos> list = getOrCreate(taskId);
        synchronized (list) {
            list.add(step);
        }
    }

    @Override
    public List<HistoryInfos> loadByTaskId(String taskId) {
        List<HistoryInfos> list = store.get(taskId);
        if (list == null) return List.of();
        synchronized (list) {
            return new ArrayList<>(list);
        }
    }

    @Override
    public void replace(String taskId, List<HistoryInfos> compacted) {
        List<HistoryInfos> list = getOrCreate(taskId);
        synchronized (list) {
            list.clear();
            list.addAll(compacted);
        }
    }

    @Override
    public void clear(String taskId) {
        store.remove(taskId);
    }
}
