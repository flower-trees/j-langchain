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

package org.salt.jlangchain.ai.vendor.ollama;

import org.salt.jlangchain.ai.client.stream.HttpStreamClient;
import org.salt.jlangchain.ai.common.param.AiChatInput;
import org.salt.jlangchain.ai.common.param.AiChatOutput;
import org.salt.jlangchain.ai.chat.strategy.BaseAiChatActuator;
import org.salt.jlangchain.ai.chat.strategy.ListenerStrategy;
import org.salt.jlangchain.ai.vendor.ollama.param.OllamaRequest;
import org.springframework.beans.factory.annotation.Value;

import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.stream.Collectors;

public class OllamaActuator extends BaseAiChatActuator<OllamaRequest> {

    @Value("${models.ollama.chat-url}")
    private String chatUrl;

    @Value("${models.ollama.chat-key}")
    private String chatKey;

    public OllamaActuator(HttpStreamClient commonHttpClient) {
        super(commonHttpClient);
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
    protected ListenerStrategy getListenerStrategy(AiChatInput aiChatInput, Consumer<AiChatOutput> responder, BiConsumer<AiChatInput, AiChatOutput> callback) {
        return new OllamaListener(aiChatInput, responder, callback);
    }

    public OllamaRequest convert(AiChatInput aiChatInput) {
        OllamaRequest request = new OllamaRequest();
        request.setModel(aiChatInput.getModel());
        request.setStream(aiChatInput.isStream());

        List<OllamaRequest.Message> doubaoMessages = aiChatInput.getMessages().stream()
                .map(this::convertMessage)
                .collect(Collectors.toList());
        request.setMessages(doubaoMessages);
        OllamaRequest.Options options = new OllamaRequest.Options();
        options.setTemperature(0.3);
        request.setOptions(options);

        return request;
    }

    private OllamaRequest.Message convertMessage(AiChatInput.Message aiChatMessage) {
        OllamaRequest.Message message = new OllamaRequest.Message();
        message.setRole(aiChatMessage.getRole());
        message.setContent(aiChatMessage.getContent());
        return message;
    }
}
