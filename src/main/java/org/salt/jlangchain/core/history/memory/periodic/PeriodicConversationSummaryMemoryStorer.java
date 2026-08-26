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

package org.salt.jlangchain.core.history.memory.periodic;

import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.extern.slf4j.Slf4j;
import org.salt.jlangchain.core.history.HistoryInfos;
import org.salt.jlangchain.core.history.memory.ConversationMemoryStorerBase;
import org.salt.jlangchain.core.history.storage.ConversationStorage;
import org.salt.jlangchain.core.llm.BaseChatModel;
import org.salt.jlangchain.core.message.BaseMessage;
import org.salt.jlangchain.core.message.MessageType;
import org.salt.jlangchain.core.prompt.value.ChatPromptValue;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Keeps raw turns unmodified until they reach {@code maxSize} — here used as a trigger
 * threshold, not a running cap (see {@link ConversationMemoryStorerBase#maxSize}'s per-strategy
 * semantics) — then compresses the WHOLE accumulated batch into the rolling summary in ONE LLM
 * call and clears the buffer entirely.
 *
 * <p>Contrast with {@code ConversationSummaryBufferMemoryStorer} (the {@code summarybuffer}
 * package), which compresses exactly one — the oldest — turn every time the buffer is exceeded:
 * a rolling, per-turn cost that keeps triggering on every subsequent turn once the buffer first
 * fills. This strategy trades "always exactly maxSize-1 raw turns visible" for far fewer
 * summarization LLM calls on long sessions — a 50-turn session with {@code maxSize=20} triggers
 * 2 compaction calls here vs. ~40 with the rolling strategy. Closer to how Claude Code's
 * {@code /compact} behaves (batched, not continuous).
 *
 * <pre>
 *   Before: [Summary(T1..T19)]                         maxSize=20, buffer=0
 *   Add T20..T39 one at a time: buffer grows to 20 on the 20th add
 *                                → compress ALL 20 into summary at once, buffer clears
 *   After:  [Summary(T1..T39)]                          buffer=0
 * </pre>
 */
@Slf4j
@Data
@EqualsAndHashCode(callSuper = true)
public class PeriodicConversationSummaryMemoryStorer extends ConversationMemoryStorerBase {

    private static final String SYSTEM_PROMPT =
            "Progressively summarize the conversation provided, merging it with the existing summary and returning a new concise summary. " +
            "Preserve key facts, names, preferences, and decisions. Reply with only the new summary text.";

    private BaseChatModel llm;

    @Override
    public void storeHistory(HistoryInfos historyInfos) {
        List<HistoryInfos> all = storage.loadAll(appId, userId, sessionId);

        // Separate summary from buffer turns
        HistoryInfos summaryEntry = null;
        List<HistoryInfos> buffer = new ArrayList<>();
        for (HistoryInfos h : all) {
            if (h.getType() == HistoryInfos.Type.SUMMARY) {
                summaryEntry = h;
            } else {
                buffer.add(h);
            }
        }

        // Add new turn to buffer
        buffer.add(historyInfos);

        // Periodic trigger: buffer reached maxSize -> compress the WHOLE batch in one call,
        // then clear it (not "remove the oldest one", unlike the rolling strategy).
        if (buffer.size() >= maxSize) {
            summaryEntry = mergeIntoSummary(summaryEntry, buffer);
            buffer = new ArrayList<>();
        }

        // Persist: [summary (if any)] + buffer
        List<HistoryInfos> newState = new ArrayList<>();
        if (summaryEntry != null) {
            newState.add(summaryEntry);
        }
        newState.addAll(buffer);
        storage.replace(appId, userId, sessionId, newState);
    }

    private HistoryInfos mergeIntoSummary(HistoryInfos existing, List<HistoryInfos> batch) {
        String existingText = existing == null ? ""
                : existing.getMessages().get(0).getContent()
                          .replaceFirst("^Conversation summary:\\s*", "");

        String batchText = batch.stream().map(this::formatTurn).collect(Collectors.joining("\n\n"));
        String updatedSummary = callSummaryLlm(existingText, batchText);

        return HistoryInfos.builder()
                .type(HistoryInfos.Type.SUMMARY)
                .messages(List.of(BaseMessage.fromMessage(MessageType.SYSTEM.getCode(),
                        "Conversation summary: " + updatedSummary)))
                .build();
    }

    private String callSummaryLlm(String existingSummary, String newTurnsText) {
        String userContent = (existingSummary.isBlank() ? "" : "Existing summary:\n" + existingSummary + "\n\n") +
                "New conversation:\n" + newTurnsText;

        ChatPromptValue prompt = ChatPromptValue.builder()
                .messages(List.of(
                        BaseMessage.fromMessage(MessageType.SYSTEM.getCode(), SYSTEM_PROMPT),
                        BaseMessage.fromMessage(MessageType.HUMAN.getCode(), userContent)
                ))
                .build();

        return llm.invoke(prompt).getContent();
    }

    private String formatTurn(HistoryInfos historyInfos) {
        StringBuilder sb = new StringBuilder();
        for (BaseMessage msg : historyInfos.getMessages()) {
            sb.append(msg.getRole()).append(": ").append(msg.getContent()).append("\n");
        }
        return sb.toString().trim();
    }

    public static Builder builder() { return new Builder(); }

    public static final class Builder {
        private Long appId;
        private Long userId;
        private Long sessionId;
        private Integer maxSize;
        private ConversationStorage storage;
        private BaseChatModel llm;

        public Builder appId(Long v)                 { this.appId = v;     return this; }
        public Builder userId(Long v)                { this.userId = v;    return this; }
        public Builder sessionId(Long v)             { this.sessionId = v; return this; }
        public Builder maxSize(Integer v)            { this.maxSize = v;   return this; }
        public Builder storage(ConversationStorage v){ this.storage = v;   return this; }
        public Builder llm(BaseChatModel v)          { this.llm = v;       return this; }

        public PeriodicConversationSummaryMemoryStorer build() {
            PeriodicConversationSummaryMemoryStorer s = new PeriodicConversationSummaryMemoryStorer();
            if (appId != null)     s.setAppId(appId);
            if (userId != null)    s.setUserId(userId);
            if (sessionId != null) s.setSessionId(sessionId);
            if (maxSize != null)   s.setMaxSize(maxSize);
            if (storage != null)   s.setStorage(storage);
            if (llm != null)       s.setLlm(llm);
            return s;
        }
    }
}
