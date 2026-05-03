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
import org.salt.jlangchain.core.history.HistoryStorerBase;
import org.salt.jlangchain.core.history.storage.ConversationStorage;

/**
 * Base class for all conversation memory storers.
 * Subclasses implement {@link #storeHistory(org.salt.jlangchain.core.history.HistoryInfos)} with strategy-specific logic.
 * {@code maxSize} semantics differ by strategy:
 * <ul>
 *   <li>BufferWindow — max total turns kept</li>
 *   <li>SummaryMemory — not used (every turn triggers summarization)</li>
 *   <li>SummaryBuffer — max buffer turns (summary entry excluded)</li>
 * </ul>
 */
@Slf4j
@Data
@EqualsAndHashCode(callSuper = true)
public abstract class ConversationMemoryStorerBase extends HistoryStorerBase {

    // appId / userId / sessionId inherited from HistoryBase

    protected ConversationStorage storage;
    protected Integer maxSize = 100;
}
