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

import org.salt.jlangchain.ai.chat.strategy.BaseAiChatActuator;
import org.salt.jlangchain.ai.chat.strategy.ListenerStrategy;
import org.salt.jlangchain.ai.client.stream.HttpStreamClient;
import org.salt.jlangchain.ai.common.param.AiChatInput;
import org.salt.jlangchain.ai.common.param.AiChatOutput;
import org.salt.jlangchain.ai.vendor.ollama.param.OllamaRequest;
import org.salt.jlangchain.ai.vendor.ollama.param.OllamaResponse;
import org.springframework.beans.factory.annotation.Value;

import java.util.function.BiConsumer;
import java.util.function.Consumer;

public class OllamaActuator extends BaseAiChatActuator<OllamaResponse, OllamaRequest> {

    @Value("${models.ollama.chat-url:http://localhost:11434/api/chat}")
    private String chatUrl;

    @Value("${models.ollama.chat-key:${OLLAMA_KEY1:}}")
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

    protected OllamaRequest convertRequest(AiChatInput aiChatInput) {
        return OllamaConvert.convertRequest(aiChatInput);
    }

    @Override
    protected AiChatOutput convertResponse(OllamaResponse response) {
        return OllamaConvert.convertResponse(response);
    }

    protected Class<OllamaResponse> responseType() {
        return OllamaResponse.class;
    }
}
