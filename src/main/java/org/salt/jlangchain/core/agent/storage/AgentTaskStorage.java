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

package org.salt.jlangchain.core.agent.storage;

import org.salt.jlangchain.core.history.HistoryInfos;

import java.util.List;

/**
 * Storage backend for agent task steps.
 *
 * <p>Each record is a {@link HistoryInfos} entry whose {@code parentId} points to
 * the owning task (either a ConversationStorage record id or a parent agent task id),
 * forming a tree:
 * <pre>
 *   ConversationStorage HistoryInfos (id=A, parentId=null)
 *     └─ AgentTaskStorage HistoryInfos (id=B, parentId=A, type=AGENT_STEP)
 *          └─ AgentTaskStorage HistoryInfos (id=C, parentId=B, type=AGENT_STEP)  ← nested agent
 * </pre>
 *
 * <p>Extend this interface to add MySQL / Redis / PostgreSQL backends.
 */
public interface AgentTaskStorage {

    /** Append one step record under the given task. */
    void append(String taskId, HistoryInfos step);

    /** Load all step records directly under the given task, ordered by createdAt. */
    List<HistoryInfos> loadByTaskId(String taskId);

    /**
     * Atomically replace all step records for the given task with a compacted list.
     * Used when older steps are compressed into a TASK_SUMMARY entry.
     */
    void replace(String taskId, List<HistoryInfos> compacted);

    /** Remove all step records for the given task. */
    void clear(String taskId);
}
