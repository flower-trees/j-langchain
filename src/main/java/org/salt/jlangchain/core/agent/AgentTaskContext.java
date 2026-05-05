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

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.salt.jlangchain.core.history.HistoryInfos;
import org.salt.jlangchain.core.history.storage.AgentTaskStorage;
import org.salt.jlangchain.core.llm.BaseChatModel;
import org.salt.jlangchain.core.message.BaseMessage;
import org.salt.jlangchain.core.message.HumanMessage;
import org.salt.jlangchain.core.message.MessageType;
import org.salt.jlangchain.core.message.SystemMessage;
import org.salt.jlangchain.core.prompt.value.ChatPromptValue;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * In-memory working memory for one agent task execution.
 *
 * <p>Holds the original task and a sliding window of recent steps, compressing
 * older steps into {@code earlyStepsSummary} to prevent context overflow.
 *
 * <p>Lifecycle: created at the start of {@code invoke()}, discarded when the call returns.
 * Optionally persisted to {@link AgentTaskStorage} for debugging / auditing.
 */
@Slf4j
@Getter
public class AgentTaskContext {

    /** Unique id for this task execution (used as taskId in AgentTaskStorage). */
    private final String taskId;

    /**
     * Id of the parent record (ConversationStorage entry or parent agent task).
     * Null if this is a top-level agent call not linked to a conversation.
     */
    private final String parentId;

    /** The original user question/task — never trimmed. */
    private final String originalTask;

    /** Optional system prompt carried through the task. */
    private final String systemPrompt;

    /** Summary of steps that fell outside the sliding window. May be null. */
    private String earlyStepsSummary;

    /** Recent steps within the sliding window. */
    private final List<AgentStep> recentSteps = new ArrayList<>();

    /** Maximum number of steps to keep in the sliding window before compressing. */
    private final int windowSize;

    /**
     * For ReAct text agents: the base prompt text (template + question, before any scratchpad).
     * Set on the first executeTool call; never overwritten.
     */
    private String reactBasePromptText;

    private AgentTaskContext(String taskId, String parentId, String originalTask,
                             String systemPrompt, int windowSize) {
        this.taskId       = taskId;
        this.parentId     = parentId;
        this.originalTask = originalTask;
        this.systemPrompt = systemPrompt;
        this.windowSize   = windowSize;
    }

    public static AgentTaskContext create(String originalTask, String systemPrompt, int windowSize) {
        return new AgentTaskContext(UUID.randomUUID().toString(), null, originalTask, systemPrompt, windowSize);
    }

    public static AgentTaskContext create(String originalTask, String systemPrompt,
                                          int windowSize, String parentId) {
        return new AgentTaskContext(UUID.randomUUID().toString(), parentId, originalTask, systemPrompt, windowSize);
    }

    /**
     * Add a completed step.
     * If recentSteps exceeds {@code windowSize}, the oldest step is moved into
     * {@code earlyStepsSummary} using the provided summarizer (if any).
     */
    public void addStep(AgentStep step, BaseChatModel summarizer, AgentTaskStorage storage) {
        recentSteps.add(step);

        // Persist to storage if configured
        if (storage != null) {
            List<BaseMessage> msgs = step.toMessages();
            if (!msgs.isEmpty()) {
                storage.append(taskId, HistoryInfos.builder()
                        .type(HistoryInfos.Type.AGENT_STEP)
                        .parentId(taskId)
                        .messages(msgs)
                        .build());
            }
        }

        // Compress if window exceeded
        if (recentSteps.size() > windowSize) {
            compressEarliestStep(summarizer, storage);
        }
    }

    /**
     * Build the message list to feed into the LLM for the next iteration.
     * Layout:
     * <ol>
     *   <li>System message (with earlyStepsSummary appended if present)</li>
     *   <li>HumanMessage (originalTask)</li>
     *   <li>Recent step messages (AI tool-call + tool results) in order</li>
     * </ol>
     */
    public List<BaseMessage> buildMessages() {
        List<BaseMessage> messages = new ArrayList<>();

        // System message
        if (StringUtils.isNotBlank(systemPrompt) || StringUtils.isNotBlank(earlyStepsSummary)) {
            String sysContent = StringUtils.defaultString(systemPrompt, "");
            if (StringUtils.isNotBlank(earlyStepsSummary)) {
                sysContent = sysContent.isBlank()
                        ? earlyStepsSummary
                        : sysContent + "\n\n" + earlyStepsSummary;
            }
            messages.add(SystemMessage.builder().content(sysContent).build());
        }

        // Original task
        messages.add(HumanMessage.builder().content(originalTask).build());

        // Recent steps
        for (AgentStep step : recentSteps) {
            messages.addAll(step.toMessages());
        }

        return messages;
    }

    /**
     * Build a reconstructed ChatPromptValue from the current context state.
     * Called before each LLM invocation in the agent loop.
     */
    public ChatPromptValue buildChatPromptValue() {
        return ChatPromptValue.builder().messages(buildMessages()).build();
    }

    /**
     * Build the scratchpad text for ReAct text agents by concatenating all step texts.
     * Prefixed with earlyStepsSummary if present.
     */
    public String buildScratchpadText() {
        StringBuilder sb = new StringBuilder();
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

    /**
     * Save the base prompt text for ReAct agents on the first tool-execution call.
     * Subsequent calls are no-ops (the base text must not change once set).
     */
    public void initReactBasePromptText(String text) {
        if (this.reactBasePromptText == null) {
            this.reactBasePromptText = text;
        }
    }

    /**
     * Rebuild the full ReAct prompt text from the saved base and the current scratchpad.
     * Used by AgentExecutor to replace the growing in-place text each iteration.
     */
    public String buildReactPromptText() {
        if (reactBasePromptText == null) return "";
        return reactBasePromptText + buildScratchpadText();
    }

    // ── private helpers ──────────────────────────────────────────────────────

    private void compressEarliestStep(BaseChatModel summarizer, AgentTaskStorage storage) {
        AgentStep oldest = recentSteps.remove(0);
        String stepText = stepToText(oldest);

        String previousSummary = StringUtils.defaultString(earlyStepsSummary, "");

        if (summarizer != null) {
            try {
                String prompt = buildSummarizationPrompt(previousSummary, stepText);
                Object result = summarizer.invoke(prompt);
                earlyStepsSummary = result != null ? result.toString() : previousSummary + "\n" + stepText;
            } catch (Exception e) {
                log.warn("AgentTaskContext: step summarization failed, falling back to concatenation", e);
                earlyStepsSummary = previousSummary.isBlank()
                        ? stepText
                        : previousSummary + "\n" + stepText;
            }
        } else {
            earlyStepsSummary = previousSummary.isBlank()
                    ? stepText
                    : previousSummary + "\n" + stepText;
        }

        // Persist updated summary to storage
        if (storage != null) {
            List<HistoryInfos> stored = storage.loadByTaskId(taskId);
            List<HistoryInfos> compacted = new ArrayList<>();
            compacted.add(HistoryInfos.builder()
                    .type(HistoryInfos.Type.TASK_SUMMARY)
                    .parentId(taskId)
                    .messages(List.of(org.salt.jlangchain.core.message.BaseMessage.fromMessage(
                            MessageType.AI.getCode(), earlyStepsSummary)))
                    .build());
            // Keep the remaining (non-compressed) persisted steps
            if (stored.size() > 1) {
                compacted.addAll(stored.subList(1, stored.size()));
            }
            storage.replace(taskId, compacted);
        }
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

    private static String buildSummarizationPrompt(String existingSummary, String newStep) {
        if (existingSummary.isBlank()) {
            return "Summarize the following agent step concisely:\n\n" + newStep;
        }
        return "You have an existing summary of earlier agent steps:\n" + existingSummary
                + "\n\nIncorporate the following new step into the summary:\n" + newStep
                + "\n\nProvide the updated summary:";
    }
}
