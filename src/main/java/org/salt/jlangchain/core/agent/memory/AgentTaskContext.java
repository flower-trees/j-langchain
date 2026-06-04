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

package org.salt.jlangchain.core.agent.memory;

import org.salt.jlangchain.core.message.BaseMessage;
import org.salt.jlangchain.core.prompt.value.ChatPromptValue;
import org.salt.jlangchain.ai.common.param.AiTokenUsage;
import org.salt.jlangchain.core.agent.AgentExecutionMetrics;

import java.util.Collections;
import java.util.List;

/**
 * Per-invocation working memory for one agent task execution.
 *
 * <p>Instances are created by {@link AgentContext#create} at the start of each
 * {@code invoke()} call and stored in ContextBus for the duration of that call.
 */
public interface AgentTaskContext {

    /** Add a completed step; implementations manage windowing and optional storage internally. */
    void addStep(AgentStep step);

    /** Build the full message list for the next LLM call (function-calling agents). */
    List<BaseMessage> buildMessages();

    /** Build a ChatPromptValue from {@link #buildMessages()}. */
    default ChatPromptValue buildChatPromptValue() {
        return ChatPromptValue.builder().messages(buildMessages()).build();
    }

    /** ReAct agents: save the base prompt text on the first tool-execution call. No-op if already set. */
    void initReactBasePromptText(String text);

    /** ReAct agents: rebuild the full prompt text = base + accumulated scratchpad. */
    String buildReactPromptText();

    /** Unique id for this task execution instance. */
    String getTaskId();

    /** Returns the steps completed so far (unmodifiable). Used by stop/resume. */
    default List<AgentStep> getCompletedSteps() {
        return Collections.emptyList();
    }

    /**
     * Append a new human turn after the accumulated steps.
     *
     * <p>Called during resume when the caller provides follow-up input (e.g. a user
     * confirmation {@code "y"} or {@code "n"}). The message is placed at the end of
     * {@link #buildMessages()} so the chronological order is preserved:
     * original request → completed steps → user follow-up.
     * The default no-op is provided for backward compatibility with custom implementations.
     */
    default void addHumanTurn(String message) {}

    /** Add token usage from one model call to this task's aggregate usage. */
    default void addTokenUsage(AiTokenUsage usage) {}

    /** Add tool-call count to this task's aggregate usage metadata. */
    default void addToolCalls(long count) {}

    /** Add model-call wall-clock duration to this task's aggregate metrics. */
    default void addLlmDuration(long durationMs) {}

    /** Add tool-call wall-clock duration to this task's aggregate metrics. */
    default void addToolDuration(long durationMs) {}

    /** Mark this task as ended; no-op for custom contexts that do not track timing. */
    default void markEnded() {}

    /** Returns aggregate token usage for this task. */
    default AiTokenUsage getTokenUsage() {
        return AiTokenUsage.empty();
    }

    /** Returns aggregate execution metrics for this task. */
    default AgentExecutionMetrics getExecutionMetrics() {
        return new AgentExecutionMetrics();
    }
}
