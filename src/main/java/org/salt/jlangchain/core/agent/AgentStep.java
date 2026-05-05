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

import lombok.Data;
import org.salt.jlangchain.core.message.AIMessage;
import org.salt.jlangchain.core.message.BaseMessage;

import java.util.ArrayList;
import java.util.List;

/**
 * Represents one completed step in an agent's execution loop.
 *
 * <p>For function-calling agents ({@link McpAgentExecutor}):
 * {@code aiMessage} holds the model's tool-call response; {@code toolResults} holds
 * the tool execution outputs. Both are replayed as messages when rebuilding the prompt.
 *
 * <p>For ReAct text agents ({@link AgentExecutor}):
 * {@code scratchpadText} holds the full thought/action/observation block as raw text.
 */
@Data
public class AgentStep {

    /** The AI's tool-call message (function-calling mode). Null for ReAct text mode. */
    private AIMessage aiMessage;

    /** Tool execution result messages, one per tool call (function-calling mode). */
    private List<BaseMessage> toolResults;

    /** Raw thought/action/observation text block (ReAct text mode). Null for function-calling mode. */
    private String scratchpadText;

    private AgentStep() {
    }

    /** Create a step for function-calling agents (McpAgentExecutor). */
    public static AgentStep ofFunctionCall(AIMessage aiMessage, List<BaseMessage> toolResults) {
        AgentStep step = new AgentStep();
        if (aiMessage != null && aiMessage.getContent() == null) {
            aiMessage.setContent("");
        }
        step.aiMessage = aiMessage;
        step.toolResults = toolResults != null ? toolResults : List.of();
        return step;
    }

    /** Create a step for ReAct text agents (AgentExecutor). */
    public static AgentStep ofReAct(String scratchpadText) {
        AgentStep step = new AgentStep();
        step.scratchpadText = scratchpadText;
        step.toolResults = List.of();
        return step;
    }

    /**
     * Convert this step to an ordered message list for prompt reconstruction.
     * Returns an empty list for ReAct text steps (they are appended as raw text instead).
     */
    public List<BaseMessage> toMessages() {
        List<BaseMessage> msgs = new ArrayList<>();
        if (aiMessage != null) msgs.add(aiMessage);
        if (toolResults != null) msgs.addAll(toolResults);
        return msgs;
    }
}
