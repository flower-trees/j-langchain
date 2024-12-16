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

import org.salt.jlangchain.ai.vendor.ollama.param.OllamaRequest;
import org.salt.jlangchain.ai.client.stream.HttpStreamClient;
import org.salt.jlangchain.ai.strategy.AiChatActuator;
import org.salt.jlangchain.ai.common.param.AiChatInput;
import org.salt.jlangchain.ai.common.param.AiChatOutput;
import org.salt.jlangchain.utils.JsonUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.stream.Collectors;

@Component
public class OllamaActuator implements AiChatActuator {

    @Value("${models.ollama.chat-url}")
    private String chatUrl;

    @Value("${models.ollama.chat-key}")
    private String chatKey;

    @Autowired
    HttpStreamClient commonHttpClient;

    @Override
    public AiChatOutput stream(AiChatInput aiChatInput, Consumer<AiChatOutput> responder) {
        Map<String, String> headers = new HashMap<>();
        headers.put("Content-Type", "application/json");
        headers.put("Authorization", "Bearer " + chatKey);

        OllamaRequest request = convert(aiChatInput);

        AtomicReference<AiChatOutput> r = new AtomicReference<>();
        commonHttpClient.request(chatUrl, JsonUtil.toJson(request), headers, List.of(new OllamaListener(aiChatInput, responder, (aiChatDto1, aiChatResponse) -> { r.set(aiChatResponse); })));
        return r.get();
    }

    @Override
    public void astream(AiChatInput aiChatInput, Consumer<AiChatOutput> responder) {
        Map<String, String> headers = new HashMap<>();
        headers.put("Content-Type", "application/json");
        headers.put("Authorization", "Bearer " + chatKey);

        OllamaRequest request = convert(aiChatInput);

        commonHttpClient.call(chatUrl, JsonUtil.toJson(request), headers, List.of(new OllamaListener(aiChatInput, responder, null)));
    }

    public static OllamaRequest convert(AiChatInput aiChatInput) {
        OllamaRequest request = new OllamaRequest();
        request.setModel(aiChatInput.getModel());
        request.setStream(aiChatInput.isStream());

        List<OllamaRequest.Message> doubaoMessages = aiChatInput.getMessages().stream()
                .map(OllamaActuator::convertMessage)
                .collect(Collectors.toList());
        request.setMessages(doubaoMessages);
        OllamaRequest.Options options = new OllamaRequest.Options();
        options.setTemperature(0.3);
        request.setOptions(options);

        return request;
    }

    private static OllamaRequest.Message convertMessage(AiChatInput.Message aiChatMessage) {
        OllamaRequest.Message message = new OllamaRequest.Message();
        message.setRole(aiChatMessage.getRole());
        message.setContent(aiChatMessage.getContent());
        return message;
    }
}
