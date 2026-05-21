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

import org.salt.jlangchain.core.agent.memory.AgentTaskContext;

import java.util.Map;

/**
 * Thrown when a tool signals that the agent should pause and wait for external input.
 *
 * <p>This is an agent-initiated pause (e.g. waiting for user input, waiting for
 * human approval). The specific semantics are defined by the upper-layer application —
 * the framework only provides the mechanism.
 *
 * <p>The {@code reason} string is application-defined (e.g. {@code "wait_user"},
 * {@code "need_approval"}). The {@code payload} carries any additional context
 * the application needs to handle the pause. The {@code partialContext} is saved
 * so execution can be resumed via the preloadedCtx mechanism.
 *
 * <p>Example usage in an upper-layer tool:
 * <pre>{@code
 * Tool waitUserTool = Tool.builder()
 *     .name("wait_for_user")
 *     .func(args -> {
 *         AgentTaskContext ctx = ContextBus.get().getTransmit(CallInfo.AGENT_TASK_CTX.name());
 *         throw new AgentPauseException("wait_user", Map.of("question", args.get("question")), ctx);
 *     })
 *     .build();
 * }</pre>
 */
public class AgentPauseException extends AgentException {

    private final String reason;
    private final Map<String, Object> payload;
    private final AgentTaskContext partialContext;

    public AgentPauseException(String reason, Map<String, Object> payload, AgentTaskContext partialContext) {
        super("Agent paused: " + reason);
        this.reason = reason;
        this.payload = payload != null ? payload : Map.of();
        this.partialContext = partialContext;
    }

    public AgentPauseException(String reason, AgentTaskContext partialContext) {
        this(reason, Map.of(), partialContext);
    }

    public String getReason() {
        return reason;
    }

    public Map<String, Object> getPayload() {
        return payload;
    }

    public AgentTaskContext getPartialContext() {
        return partialContext;
    }
}
