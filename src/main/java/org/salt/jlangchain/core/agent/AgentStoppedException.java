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
import org.salt.jlangchain.core.agent.memory.AgentStep;
import org.salt.jlangchain.core.agent.memory.AgentTaskContext;

import java.util.List;

/**
 * Thrown when an agent loop is stopped via {@link McpAgentExecutor#stop()} or
 * {@link AgentExecutor#stop()}. Carries the partial {@link AgentTaskContext}
 * accumulated before the stop, allowing callers to inspect completed steps or
 * resume execution via
 * {@link McpAgentExecutor#invoke(String, java.util.concurrent.atomic.AtomicBoolean, AgentTaskContext)}.
 *
 * <p>Implements {@link FlowControlException} so the flow engine logs it at DEBUG
 * level rather than WARN — it is an intentional signal, not an error.
 */
public class AgentStoppedException extends RuntimeException implements FlowControlException {

    private final AgentTaskContext partialContext;

    public AgentStoppedException(String message, AgentTaskContext partialContext) {
        super(message);
        this.partialContext = partialContext;
    }

    /** The partial context at the moment of stop (may be null if stop fired before initContext). */
    public AgentTaskContext getPartialContext() {
        return partialContext;
    }

    /** Convenience: returns completed steps, or an empty list if context is null. */
    public List<AgentStep> getCompletedSteps() {
        return partialContext != null ? partialContext.getCompletedSteps() : List.of();
    }
}
