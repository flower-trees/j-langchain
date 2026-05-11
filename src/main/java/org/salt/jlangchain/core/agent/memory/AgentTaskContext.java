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
}
