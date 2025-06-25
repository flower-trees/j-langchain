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

package org.salt.jlangchain.ai.vendor.doubao.coze;

import org.salt.jlangchain.ai.chat.openai.RoleType;
import org.salt.jlangchain.ai.chat.sse.SseBaseAiChatActuator;
import org.salt.jlangchain.ai.chat.sse.SseListenerStrategy;
import org.salt.jlangchain.ai.client.stream.HttpSseClient;
import org.salt.jlangchain.ai.common.param.AiChatInput;
import org.salt.jlangchain.ai.common.param.AiChatOutput;
import org.salt.jlangchain.ai.vendor.doubao.coze.dto.AdditionalMessage;
import org.salt.jlangchain.ai.vendor.doubao.coze.dto.ChatRequest;
import org.salt.jlangchain.ai.vendor.doubao.coze.dto.MessageCompletedEvent;
import org.springframework.beans.factory.annotation.Value;

import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.stream.Collectors;

public class CozeActuator extends SseBaseAiChatActuator<MessageCompletedEvent, ChatRequest> {

    @Value("${models.coze.chat-url:https://api.coze.cn/v3/chat}")
    private String chatUrl;

    @Value("${models.coze.chat-key:${COZE_KEY:}}")
    private String chatKey;

    public CozeActuator(HttpSseClient httpSseClient) {
        super(httpSseClient);
    }

    @Override
    protected String getChatUrl() {
        return chatUrl;
    }

    @Override
    protected String getChatKey() {
        return chatKey;
    }

    @Override
    protected ChatRequest convertRequest(AiChatInput aiChatInput) {
        ChatRequest chatRequest = new ChatRequest();
        chatRequest.setBotId(aiChatInput.getBotId());
        chatRequest.setUserId(aiChatInput.getUserId());
        chatRequest.setStream(aiChatInput.isStream());
        chatRequest.setAdditionalMessages(aiChatInput.getMessages().stream()
                .map(message -> new AdditionalMessage(message.getContent(), "text", message.getRole(), message.getRole().equalsIgnoreCase(RoleType.USER.getCode()) ?  "question" : "answer"))
                .collect(Collectors.toList()));
        return chatRequest;
    }

    @Override
    protected AiChatOutput convertResponse(MessageCompletedEvent response) {
        return null;
    }

    @Override
    protected Class<MessageCompletedEvent> responseType() {
        return MessageCompletedEvent.class;
    }

    @Override
    protected SseListenerStrategy getListenerStrategy(AiChatInput aiChatInput, Consumer<AiChatOutput> responder, BiConsumer<AiChatInput, AiChatOutput> callback) {
        return new CozeListener(aiChatInput, responder, callback);
    }
}