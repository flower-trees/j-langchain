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
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class MemoryHistory {

    protected static final Map<String, Map<String, List<HistoryInfos>>> historyMap = new HashMap<>();

    protected static void init(Long userId, Long sessionId) {
        if (!MemoryHistory.historyMap.containsKey(String.valueOf(userId))) {
            MemoryHistory.historyMap.put(String.valueOf(userId), new HashMap<>());
        }

        if (!MemoryHistory.historyMap.get(String.valueOf(userId)).containsKey(String.valueOf(sessionId))) {
            MemoryHistory.historyMap.get(String.valueOf(userId)).put(String.valueOf(sessionId), new ArrayList<>());
        }
    }
}
