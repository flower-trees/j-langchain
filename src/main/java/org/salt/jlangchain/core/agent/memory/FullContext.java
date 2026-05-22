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

import org.apache.commons.lang3.StringUtils;
import org.salt.jlangchain.ai.common.param.AiTokenUsage;
import org.salt.jlangchain.core.message.BaseMessage;
import org.salt.jlangchain.core.message.HumanMessage;
import org.salt.jlangchain.core.message.SystemMessage;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * Default no-compression {@link AgentContext} implementation.
 *
 * <p>Each call to {@link #create} returns a fresh {@link Session} that accumulates
 * all steps without any windowing or summarization.
 * This is used automatically when no {@code context()} is configured on the executor.
 */
public class FullContext implements AgentContext {

    private FullContext() {}

    public static FullContext build() {
        return new FullContext();
    }

    @Override
    public AgentTaskContext create(String question, String systemPrompt) {
        return new Session(question, systemPrompt);
    }

    // ── Per-invocation context ───────────────────────────────────────────────────

    static class Session implements AgentTaskContext {

        private final String taskId = UUID.randomUUID().toString();
        private final String originalTask;
        private final String systemPrompt;
        private final List<AgentStep> recentSteps = new ArrayList<>();
        private final AiTokenUsage tokenUsage = AiTokenUsage.empty();
        private String resumeInput;
        private String reactBasePromptText;

        Session(String question, String systemPrompt) {
            this.originalTask = question;
            this.systemPrompt = systemPrompt;
        }

        @Override
        public void addStep(AgentStep step) {
            recentSteps.add(step);
        }

        @Override
        public List<BaseMessage> buildMessages() {
            List<BaseMessage> messages = new ArrayList<>();
            if (StringUtils.isNotBlank(systemPrompt)) {
                messages.add(SystemMessage.builder().content(systemPrompt).build());
            }
            messages.add(HumanMessage.builder().content(originalTask).build());
            for (AgentStep step : recentSteps) {
                messages.addAll(step.toMessages());
            }
            if (StringUtils.isNotBlank(resumeInput)) {
                messages.add(HumanMessage.builder().content(resumeInput).build());
            }
            return messages;
        }

        @Override
        public void initReactBasePromptText(String text) {
            if (this.reactBasePromptText == null) {
                this.reactBasePromptText = text;
            }
        }

        @Override
        public String buildReactPromptText() {
            if (reactBasePromptText == null) return "";
            StringBuilder sb = new StringBuilder(reactBasePromptText);
            for (AgentStep step : recentSteps) {
                if (StringUtils.isNotBlank(step.getScratchpadText())) {
                    sb.append(step.getScratchpadText());
                }
            }
            return sb.toString();
        }

        @Override
        public String getTaskId() {
            return taskId;
        }

        @Override
        public List<AgentStep> getCompletedSteps() {
            return Collections.unmodifiableList(recentSteps);
        }

        @Override
        public void addHumanTurn(String message) {
            if (message != null) this.resumeInput = message;
        }

        @Override
        public void addTokenUsage(AiTokenUsage usage) {
            tokenUsage.add(usage);
        }

        @Override
        public void addToolCalls(long count) {
            tokenUsage.addToolCalls(count);
        }

        @Override
        public AiTokenUsage getTokenUsage() {
            return tokenUsage.copy();
        }
    }
}
