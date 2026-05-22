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

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.salt.jlangchain.ai.common.param.AiTokenUsage;
import org.salt.jlangchain.core.history.HistoryInfos;
import org.salt.jlangchain.core.agent.storage.AgentTaskStorage;
import org.salt.jlangchain.core.llm.BaseChatModel;
import org.salt.jlangchain.core.message.BaseMessage;
import org.salt.jlangchain.core.message.HumanMessage;
import org.salt.jlangchain.core.message.MessageType;
import org.salt.jlangchain.core.message.SystemMessage;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * Sliding-window {@link AgentContext} implementation.
 *
 * <p>Each call to {@link #create} returns a fresh {@link Session} that keeps the most
 * recent {@code windowSize} steps. When the window is exceeded the oldest step is
 * compressed into {@code earlyStepsSummary} using the optional {@code summarizer} LLM
 * (falls back to plain-text concatenation if not set).
 *
 * <pre>{@code
 * AgentExecutor agent = AgentExecutor.builder(chainActor)
 *     .llm(llm)
 *     .tools(tools)
 *     .context(SlidingWindowContext.builder()
 *         .windowSize(5)
 *         .summarizer(summarizerLlm)
 *         .taskStorage(inMemoryTaskStorage)
 *         .build())
 *     .build();
 * }</pre>
 */
@Slf4j
public class SlidingWindowContext implements AgentContext {

    private final int windowSize;
    private final BaseChatModel summarizer;
    private final AgentTaskStorage taskStorage;

    private SlidingWindowContext(int windowSize, BaseChatModel summarizer, AgentTaskStorage taskStorage) {
        this.windowSize  = windowSize;
        this.summarizer  = summarizer;
        this.taskStorage = taskStorage;
    }

    @Override
    public AgentTaskContext create(String question, String systemPrompt) {
        return new Session(question, systemPrompt);
    }

    // ── Per-invocation context ───────────────────────────────────────────────────

    class Session implements AgentTaskContext {

        private final String taskId = UUID.randomUUID().toString();
        private final String originalTask;
        private final String systemPrompt;
        private final List<AgentStep> recentSteps = new ArrayList<>();
        private final AiTokenUsage tokenUsage = AiTokenUsage.empty();
        private String earlyStepsSummary;
        private String resumeInput;
        private String reactBasePromptText;

        Session(String question, String systemPrompt) {
            this.originalTask = question;
            this.systemPrompt = systemPrompt;
        }

        @Override
        public void addStep(AgentStep step) {
            recentSteps.add(step);

            if (taskStorage != null) {
                List<BaseMessage> msgs = step.toMessages();
                if (!msgs.isEmpty()) {
                    taskStorage.append(taskId, HistoryInfos.builder()
                            .type(HistoryInfos.Type.AGENT_STEP)
                            .parentId(taskId)
                            .messages(msgs)
                            .build());
                }
            }

            if (recentSteps.size() > windowSize) {
                compressEarliestStep();
            }
        }

        @Override
        public List<BaseMessage> buildMessages() {
            List<BaseMessage> messages = new ArrayList<>();

            if (StringUtils.isNotBlank(systemPrompt) || StringUtils.isNotBlank(earlyStepsSummary)) {
                String sysContent = StringUtils.defaultString(systemPrompt, "");
                if (StringUtils.isNotBlank(earlyStepsSummary)) {
                    sysContent = sysContent.isBlank()
                            ? earlyStepsSummary
                            : sysContent + "\n\n" + earlyStepsSummary;
                }
                messages.add(SystemMessage.builder().content(sysContent).build());
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
            if (StringUtils.isNotBlank(earlyStepsSummary)) {
                sb.append("[Earlier steps summary]\n").append(earlyStepsSummary).append("\n\n");
            }
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

        private void compressEarliestStep() {
            AgentStep oldest = recentSteps.remove(0);
            String stepText = stepToText(oldest);
            String previous = StringUtils.defaultString(earlyStepsSummary, "");

            if (summarizer != null) {
                try {
                    Object result = summarizer.invoke(buildSummarizationPrompt(previous, stepText));
                    earlyStepsSummary = result != null ? result.toString() : concat(previous, stepText);
                } catch (Exception e) {
                    log.warn("SlidingWindowContext: summarization failed, falling back to concatenation", e);
                    earlyStepsSummary = concat(previous, stepText);
                }
            } else {
                earlyStepsSummary = concat(previous, stepText);
            }

            if (taskStorage != null) {
                List<HistoryInfos> stored = taskStorage.loadByTaskId(taskId);
                List<HistoryInfos> compacted = new ArrayList<>();
                compacted.add(HistoryInfos.builder()
                        .type(HistoryInfos.Type.TASK_SUMMARY)
                        .parentId(taskId)
                        .messages(List.of(BaseMessage.fromMessage(MessageType.AI.getCode(), earlyStepsSummary)))
                        .build());
                if (stored.size() > 1) {
                    compacted.addAll(stored.subList(1, stored.size()));
                }
                taskStorage.replace(taskId, compacted);
            }
        }

        private static String concat(String existing, String newText) {
            return existing.isBlank() ? newText : existing + "\n" + newText;
        }

        private static String stepToText(AgentStep step) {
            if (StringUtils.isNotBlank(step.getScratchpadText())) {
                return step.getScratchpadText();
            }
            StringBuilder sb = new StringBuilder();
            for (BaseMessage msg : step.toMessages()) {
                sb.append(msg.getRole()).append(": ").append(msg.getContent()).append("\n");
            }
            return sb.toString().trim();
        }

        private static String buildSummarizationPrompt(String existing, String newStep) {
            if (existing.isBlank()) {
                return "Summarize the following agent step concisely:\n\n" + newStep;
            }
            return "You have an existing summary of earlier agent steps:\n" + existing
                    + "\n\nIncorporate the following new step into the summary:\n" + newStep
                    + "\n\nProvide the updated summary:";
        }
    }

    // ── Builder ──────────────────────────────────────────────────────────────────

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private int windowSize = Integer.MAX_VALUE;
        private BaseChatModel summarizer;
        private AgentTaskStorage taskStorage;

        public Builder windowSize(int windowSize) {
            this.windowSize = windowSize;
            return this;
        }

        /** LLM used to compress older steps; falls back to plain-text concatenation if not set. */
        public Builder summarizer(BaseChatModel summarizer) {
            this.summarizer = summarizer;
            return this;
        }

        /** Optional storage for step records (debugging / auditing). */
        public Builder taskStorage(AgentTaskStorage taskStorage) {
            this.taskStorage = taskStorage;
            return this;
        }

        public SlidingWindowContext build() {
            return new SlidingWindowContext(windowSize, summarizer, taskStorage);
        }
    }
}
