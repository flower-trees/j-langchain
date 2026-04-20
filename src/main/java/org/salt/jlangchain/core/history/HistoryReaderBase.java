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

package org.salt.jlangchain.core.history;

import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections.CollectionUtils;
import org.salt.jlangchain.core.message.*;
import org.salt.jlangchain.core.prompt.value.ChatPromptValue;
import org.salt.jlangchain.core.prompt.value.StringPromptValue;

import java.util.ArrayList;
import java.util.List;

@Slf4j
@EqualsAndHashCode(callSuper = true)
@Data
public abstract class HistoryReaderBase extends HistoryBase {

    /** How many recent conversation turns (Human+AI pairs) to inject into the prompt. */
    protected Integer limit = 10;

    @Override
    public ChatPromptValue invoke(Object input) {

        ChatPromptValue chatPromptValueAll;

        if (input instanceof StringPromptValue stringPromptValue) {
            chatPromptValueAll = ChatPromptValue.builder().messages(List.of(BaseMessage.fromMessage(MessageType.HUMAN.getCode(), stringPromptValue.getText()))).build();
        } else if(input instanceof ChatPromptValue chatPromptValue) {
            chatPromptValueAll = chatPromptValue;
        } else if (input instanceof String stringPrompt) {
            chatPromptValueAll = ChatPromptValue.builder().messages(List.of(BaseMessage.fromMessage(MessageType.HUMAN.getCode(), stringPrompt))).build();
        } else {
            throw new RuntimeException("input must be StringPromptValue or ChatPromptValue or String");
        }

        List<HistoryInfos> historyInfosList = readHistory();
        if (CollectionUtils.isNotEmpty(historyInfosList)) {
            List<BaseMessage> messages = new ArrayList<>();
            chatPromptValueAll.getMessages().forEach(message -> {
                if (message instanceof SystemMessage systemMessage) {
                    messages.add(systemMessage);
                }
            });
            for (HistoryInfos historyInfos : historyInfosList) {
                historyInfos.getMessages().forEach(message -> {
                    if (message instanceof HumanMessage humanMessage) {
                        messages.add(humanMessage);
                    }
                    if (message instanceof AIMessage aiMessage) {
                        messages.add(aiMessage);
                    }
                });
            }
            chatPromptValueAll.getMessages().forEach(message -> {
                if (message instanceof HumanMessage humanMessage) {
                    messages.add(humanMessage);
                }
            });
            chatPromptValueAll.setMessages(messages);
        }

        return chatPromptValueAll;
    }

    public abstract List<HistoryInfos> readHistory();
}
