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

package org.salt.jlangchain.core.history.memory.summarybuffer;

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

/**
 * Keeps the most recent {@code maxSize} turns verbatim (the buffer).
 * The summary entry is NOT counted toward {@code maxSize}.
 *
 * <p>When the buffer exceeds {@code maxSize}, the oldest buffer turn is removed
 * and merged into the rolling summary via one LLM call:
 * <pre>
 *   Before: [Summary(T1), T2, T3, T4]   maxSize=3, buffer=3
 *   Add T5:  buffer would be 4 → compress T2 into summary
 *   After:  [Summary(T1+T2), T3, T4, T5]  buffer=3 ✓
 * </pre>
 */
@Slf4j
@Data
@EqualsAndHashCode(callSuper = true)
public class ConversationSummaryBufferMemoryStorer extends ConversationMemoryStorerBase {

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

        // Compress oldest buffer turn into summary if buffer exceeds maxSize
        if (buffer.size() > maxSize) {
            HistoryInfos oldest = buffer.remove(0);
            summaryEntry = mergeIntoSummary(summaryEntry, oldest);
        }

        // Persist: [summary (if any)] + buffer
        List<HistoryInfos> newState = new ArrayList<>();
        if (summaryEntry != null) {
            newState.add(summaryEntry);
        }
        newState.addAll(buffer);
        storage.replace(appId, userId, sessionId, newState);
    }

    private HistoryInfos mergeIntoSummary(HistoryInfos existing, HistoryInfos oldest) {
        String existingText = existing == null ? ""
                : existing.getMessages().get(0).getContent()
                          .replaceFirst("^Conversation summary:\\s*", "");

        String oldestText = formatTurn(oldest);
        String updatedSummary = callSummaryLlm(existingText, oldestText);

        return HistoryInfos.builder()
                .type(HistoryInfos.Type.SUMMARY)
                .messages(List.of(BaseMessage.fromMessage(MessageType.SYSTEM.getCode(),
                        "Conversation summary: " + updatedSummary)))
                .build();
    }

    private String callSummaryLlm(String existingSummary, String newTurnText) {
        String userContent = (existingSummary.isBlank() ? "" : "Existing summary:\n" + existingSummary + "\n\n") +
                "New conversation:\n" + newTurnText;

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

        public ConversationSummaryBufferMemoryStorer build() {
            ConversationSummaryBufferMemoryStorer s = new ConversationSummaryBufferMemoryStorer();
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
