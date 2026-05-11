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

package org.salt.jlangchain.core.history.memory.summary;

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

import java.util.List;

/**
 * After every turn, calls the LLM to produce an updated rolling summary of the entire conversation.
 * Storage always contains exactly one {@link HistoryInfos.Type#SUMMARY} entry.
 */
@Slf4j
@Data
@EqualsAndHashCode(callSuper = true)
public class ConversationSummaryMemoryStorer extends ConversationMemoryStorerBase {

    private static final String SYSTEM_PROMPT =
            "Progressively summarize the conversation provided, merging it with the existing summary and returning a new concise summary. " +
            "Preserve key facts, names, preferences, and decisions. Reply with only the new summary text.";

    private BaseChatModel llm;

    @Override
    public void storeHistory(HistoryInfos historyInfos) {
        List<HistoryInfos> all = storage.loadAll(appId, userId, sessionId);

        // Extract existing summary text (empty string if none yet)
        String existingSummary = all.stream()
                .filter(h -> h.getType() == HistoryInfos.Type.SUMMARY)
                .findFirst()
                .map(h -> h.getMessages().get(0).getContent())
                .orElse("");

        // Format the new turn as text
        String newTurnText = formatTurn(historyInfos);

        // Call LLM to produce updated summary
        String updatedSummary = callSummaryLlm(existingSummary, newTurnText);

        // Replace storage with a single SUMMARY entry
        HistoryInfos summaryEntry = HistoryInfos.builder()
                .type(HistoryInfos.Type.SUMMARY)
                .messages(List.of(BaseMessage.fromMessage(MessageType.SYSTEM.getCode(),
                        "Conversation summary: " + updatedSummary)))
                .build();

        storage.replace(appId, userId, sessionId, List.of(summaryEntry));
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
        private ConversationStorage storage;
        private BaseChatModel llm;

        public Builder appId(Long v)                 { this.appId = v;     return this; }
        public Builder userId(Long v)                { this.userId = v;    return this; }
        public Builder sessionId(Long v)             { this.sessionId = v; return this; }
        public Builder storage(ConversationStorage v){ this.storage = v;   return this; }
        public Builder llm(BaseChatModel v)          { this.llm = v;       return this; }

        public ConversationSummaryMemoryStorer build() {
            ConversationSummaryMemoryStorer s = new ConversationSummaryMemoryStorer();
            if (appId != null)     s.setAppId(appId);
            if (userId != null)    s.setUserId(userId);
            if (sessionId != null) s.setSessionId(sessionId);
            if (storage != null)   s.setStorage(storage);
            if (llm != null)       s.setLlm(llm);
            return s;
        }
    }
}
