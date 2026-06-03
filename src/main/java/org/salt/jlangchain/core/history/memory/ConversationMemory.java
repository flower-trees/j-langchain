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

import java.util.List;

/**
 * Combined read/write view of a session's conversation memory.
 * Implementations pair a {@link ConversationMemoryStorerBase} strategy with its
 * corresponding {@link ConversationMemoryReaderBase}, so callers only need one
 * object instead of two.
 *
 * <p>Implementations are NOT thread-safe: each instance is scoped to a single
 * session and must not be shared across concurrent agent executions.
 */
public interface ConversationMemory {

    /** Persist a new conversation turn. */
    void storeHistory(HistoryInfos historyInfos);

    /** Load the stored history for injection into the next prompt. */
    List<HistoryInfos> readHistory();
}
