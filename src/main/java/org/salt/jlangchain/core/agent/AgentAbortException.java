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

import org.salt.jlangchain.core.agent.memory.AgentStep;
import org.salt.jlangchain.core.agent.memory.AgentTaskContext;

import java.util.List;

/**
 * Thrown when the agent loop is forcibly terminated by a system limit.
 *
 * <p>Carries the {@link AgentAbortReason} and the partial {@link AgentTaskContext}
 * accumulated before the abort, allowing callers to inspect completed steps.
 *
 * @see AgentAbortReason
 */
public class AgentAbortException extends AgentException {

    private final AgentAbortReason reason;
    private final AgentTaskContext partialContext;

    public AgentAbortException(AgentAbortReason reason, String message, AgentTaskContext partialContext) {
        super(message);
        this.reason = reason;
        this.partialContext = partialContext;
    }

    public AgentAbortReason getReason() {
        return reason;
    }

    public AgentTaskContext getPartialContext() {
        return partialContext;
    }

    public List<AgentStep> getCompletedSteps() {
        return partialContext != null ? partialContext.getCompletedSteps() : List.of();
    }
}
