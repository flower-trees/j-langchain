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

package org.salt.jlangchain.ai.vendor.chatgpt;

import org.salt.jlangchain.ai.client.stream.HttpStreamClient;
import org.salt.jlangchain.ai.common.param.AiChatInput;
import org.salt.jlangchain.ai.common.param.AiChatOutput;
import org.salt.jlangchain.ai.strategy.AiChatActuator;
import org.salt.jlangchain.ai.strategy.ListenerStrategy;
import org.salt.jlangchain.ai.vendor.chatgpt.param.ChatGPTRequest;
import org.salt.jlangchain.utils.JsonUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.stream.Collectors;

public class ChatGPTActuator implements AiChatActuator {

    @Value("${models.chatgpt.chat-url}")
    private String chatUrl;

    @Value("${models.chatgpt.chat-key}")
    private String chatKey;

    @Autowired
    HttpStreamClient chatGPTHttpClient;

    @Override
    public AiChatOutput stream(AiChatInput aiChatInput, Consumer<AiChatOutput> responder) {

        Map<String, String> headers = new HashMap<>();
        headers.put("Content-Type", "application/json");
        headers.put("Authorization", "Bearer " + chatKey);

        ChatGPTRequest chatGPTRequest = convert(aiChatInput);

        AtomicReference<AiChatOutput> r = new AtomicReference<>();

        ListenerStrategy listener = new ChatGPTListener(aiChatInput, responder, (aiChatDto1, aiChatResponse) -> r.set(aiChatResponse));

        chatGPTHttpClient.request(chatUrl, JsonUtil.toJson(chatGPTRequest), headers, List.of(listener));

        return r.get();
    }

    @Override
    public void astream(AiChatInput aiChatInput, Consumer<AiChatOutput> responder) {
        Map<String, String> headers = new HashMap<>();
        headers.put("Content-Type", "application/json");
        headers.put("Authorization", "Bearer " + chatKey);

        ChatGPTRequest chatGPTRequest = convert(aiChatInput);

        chatGPTHttpClient.call(chatUrl, JsonUtil.toJson(chatGPTRequest), headers, List.of(new ChatGPTListener(aiChatInput, responder, null)));
    }

    public static ChatGPTRequest convert(AiChatInput aiChatInput) {
        ChatGPTRequest chatGPTRequest = new ChatGPTRequest();
        chatGPTRequest.setModel(aiChatInput.getModel());
        chatGPTRequest.setStream(aiChatInput.isStream());

        List<ChatGPTRequest.Message> chatGPTMessages = aiChatInput.getMessages().stream()
                .map(ChatGPTActuator::convertMessage)
                .collect(Collectors.toList());
        chatGPTRequest.setMessages(chatGPTMessages);

        return chatGPTRequest;
    }

    private static ChatGPTRequest.Message convertMessage(AiChatInput.Message aiChatMessage) {
        ChatGPTRequest.Message chatGPTMessage = new ChatGPTRequest.Message();
        chatGPTMessage.setRole(aiChatMessage.getRole());
        chatGPTMessage.setContent(aiChatMessage.getContent());
        return chatGPTMessage;
    }
}
