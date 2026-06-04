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

package org.salt.jlangchain.core.agent;

import org.salt.function.flow.FlowControlException;

/**
 * Base class for all agent loop stop signals.
 *
 * <p>Implements {@link FlowControlException} so the flow engine logs these at
 * DEBUG level — they are intentional control signals, not errors.
 *
 * <p>Three concrete subtypes:
 * <ul>
 *   <li>{@link AgentStoppedException} — external stop via {@code stop()}</li>
 *   <li>{@link AgentAbortException}   — system limit reached (max steps, timeout, retry exceeded)</li>
 *   <li>{@link AgentPauseException}   — agent-initiated pause (upper-layer semantic stops)</li>
 * </ul>
 */
public abstract class AgentException extends RuntimeException implements FlowControlException {

    protected AgentException(String message) {
        super(message);
    }

    protected AgentException(String message, Throwable cause) {
        super(message, cause);
    }
}
