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

package org.salt.jlangchain.core.prompt.chat;

import lombok.Builder;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.commons.text.StringSubstitutor;
import org.salt.jlangchain.core.BaseRunnable;
import org.salt.jlangchain.core.message.BaseMessage;
import org.salt.jlangchain.core.message.PlaceholderMessage;
import org.salt.jlangchain.core.prompt.value.ChatPromptValue;
import org.salt.jlangchain.utils.GroceryUtil;
import org.salt.jlangchain.utils.JsonUtil;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Builder
public class ChatPromptTemplate extends BaseChatPromptTemplate {

    List<BaseMessage> messages;

    @Override
    public ChatPromptValue invoke(Object input) {

        eventAction.eventStart(input, config);
        ChatPromptValue result = null;

        try {
            if (input != null) {
                Map<String, ?> inputMap;
                if (input instanceof Map) {
                    inputMap = (Map<String, ?>)input;
                } else if (GroceryUtil.isPlainObject(input)) {
                    inputMap = JsonUtil.toMap(input);
                } else {
                    throw new RuntimeException("input must be Map or PlainObject");
                }
                StringSubstitutor sub = new StringSubstitutor(inputMap);

                List<BaseMessage> newMessages = new ArrayList<>();

                messages.forEach(message -> {
                    if (message instanceof PlaceholderMessage) {
                        String extracted = StringUtils.substringBetween(message.getContent(), "${", "}");
                        if (StringUtils.isNotEmpty(extracted) && inputMap.containsKey(extracted)) {
                            if (inputMap.get(extracted) instanceof List) {
                                for (Object item : (List) inputMap.get(extracted)) {
                                    if (item instanceof BaseMessage itemMessage) {
                                        newMessages.add(BaseMessage.fromMessage(itemMessage.getRole(), itemMessage.getContent()));
                                    } else if (item instanceof Pair) {
                                        Pair<String, String> pair = (Pair<String, String>) item;
                                        newMessages.add(BaseMessage.fromMessage(pair.getKey(), pair.getValue()));
                                    }
                                }
                            }
                        }
                    } else {
                        newMessages.add(BaseMessage.fromMessage(message.getRole(), sub.replace(message.getContent())));
                    }
                });
                result = ChatPromptValue.builder().messages(newMessages).build();
                return result;
            }

            throw new RuntimeException("input must not be null");
        } finally {
            eventAction.eventEnd(result, config);
        }
    }

    public static BaseRunnable<ChatPromptValue, Object> fromMessages(List<?> messages) {

        List<BaseMessage> messageList = new ArrayList<>();

        for (Object message : messages) {
            if (message instanceof BaseMessage) {
                messageList.add((BaseMessage) message);
            } else if (message instanceof Pair) {
                Pair<String, String> pair = (Pair<String, String>) message;
                if (pair.getKey().equals("placeholder")) {
                    messageList.add(PlaceholderMessage.builder().content(pair.getValue()).build());
                } else {
                    messageList.add(BaseMessage.fromMessage(pair.getKey(), pair.getValue()));
                }
            }
        }

        return ChatPromptTemplate.builder().messages(messageList).build();
    }
}
