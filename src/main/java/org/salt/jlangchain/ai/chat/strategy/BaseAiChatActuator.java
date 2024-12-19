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

package org.salt.jlangchain.ai.chat.strategy;

import org.salt.jlangchain.ai.client.stream.HttpStreamClient;
import org.salt.jlangchain.ai.common.param.AiChatInput;
import org.salt.jlangchain.ai.common.param.AiChatOutput;
import org.salt.jlangchain.utils.JsonUtil;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

public abstract class BaseAiChatActuator<O, I> implements AiChatActuator {

    HttpStreamClient commonHttpClient;

    public BaseAiChatActuator(HttpStreamClient commonHttpClient) {
        this.commonHttpClient = commonHttpClient;
    }

    @Override
    public AiChatOutput invoke(AiChatInput aiChatInput) {
        Map<String, String> headers = buildHeaders();
        I request = convertRequest(aiChatInput);

        O response = commonHttpClient.request(getChatUrl(), JsonUtil.toJson(request), headers, responseType());
        return convertResponse(response);
    }

    @Override
    public AiChatOutput stream(AiChatInput aiChatInput, Consumer<AiChatOutput> responder) {
        Map<String, String> headers = buildHeaders();
        I request = convertRequest(aiChatInput);

        AtomicReference<AiChatOutput> r = new AtomicReference<>();
        commonHttpClient.stream(getChatUrl(), JsonUtil.toJson(request), headers, List.of(getListenerStrategy(aiChatInput, responder, (input, output) -> r.set(output))));
        return r.get();
    }

    @Override
    public void astream(AiChatInput aiChatInput, Consumer<AiChatOutput> responder) {
        Map<String, String> headers = buildHeaders();
        I request = convertRequest(aiChatInput);

        commonHttpClient.astream(getChatUrl(), JsonUtil.toJson(request), headers, List.of(getListenerStrategy(aiChatInput, responder, null)));
    }

    protected Map<String, String> buildHeaders() {
        return Map.of(
                "Content-Type", "application/json",
                "Authorization", "Bearer " + getChatKey()
        );
    }

    protected abstract String getChatUrl();
    protected abstract String getChatKey();
    protected abstract ListenerStrategy getListenerStrategy(AiChatInput aiChatInput, Consumer<AiChatOutput> responder, BiConsumer<AiChatInput, AiChatOutput> callback);
    protected abstract I convertRequest(AiChatInput aiChatInput);
    protected abstract AiChatOutput convertResponse(O response);
    protected abstract Class<O> responseType();
}
