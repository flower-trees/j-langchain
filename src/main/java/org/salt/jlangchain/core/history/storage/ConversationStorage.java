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

import java.util.List;

/**
 * Storage backend abstraction for conversation history.
 * Implementations handle only raw CRUD; memory strategy logic lives in the Memory classes.
 * Extend this interface to add MySQL / Redis / PostgreSQL backends.
 */
public interface ConversationStorage {

    /** Load all stored turns (including any summary entry) for the given session. */
    List<HistoryInfos> loadAll(Long appId, Long userId, Long sessionId);

    /** Append one turn to the end of the session history. */
    void append(Long appId, Long userId, Long sessionId, HistoryInfos turn);

    /**
     * Atomically replace the entire session history with the compacted list.
     * Used by summary strategies after compression.
     */
    void replace(Long appId, Long userId, Long sessionId, List<HistoryInfos> compacted);

    /** Remove all turns for the given session. */
    void clear(Long appId, Long userId, Long sessionId);
}
