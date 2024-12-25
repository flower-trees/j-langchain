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

/**
 * Base class for AI chat actuators.
 * this class is used to implement the common logic of AI chat actuators.
 * this class has the following features:
 * 1. Provide a common interface for vendor API requests.
 * 2. Provide a common interface for vendor API streaming requests.
 * 3. Provide a common interface for vendor API asynchronous streaming requests.
 */
public abstract class BaseAiChatActuator<O, I> implements AiChatActuator {

    HttpStreamClient commonHttpClient;

    public BaseAiChatActuator(HttpStreamClient commonHttpClient) {
        this.commonHttpClient = commonHttpClient;
    }

    //sync request vendor api
    @Override
    public AiChatOutput invoke(AiChatInput aiChatInput) {
        Map<String, String> headers = buildHeaders();
        I request = convertRequest(aiChatInput);

        O response = commonHttpClient.request(getChatUrl(), JsonUtil.toJson(request), headers, responseType());
        return convertResponse(response);
    }

    //sync stream request vendor api, through the ListenerStrategy to handle the stream response
    @Override
    public AiChatOutput stream(AiChatInput aiChatInput, Consumer<AiChatOutput> responder) {
        Map<String, String> headers = buildHeaders();
        I request = convertRequest(aiChatInput);

        AtomicReference<AiChatOutput> r = new AtomicReference<>();
        commonHttpClient.stream(getChatUrl(), JsonUtil.toJson(request), headers, List.of(getListenerStrategy(aiChatInput, responder, (input, output) -> r.set(output))));
        return r.get();
    }

    //async stream request vendor api, through the ListenerStrategy to handle the stream response
    @Override
    public void astream(AiChatInput aiChatInput, Consumer<AiChatOutput> responder) {
        Map<String, String> headers = buildHeaders();
        I request = convertRequest(aiChatInput);

        commonHttpClient.astream(getChatUrl(), JsonUtil.toJson(request), headers, List.of(getListenerStrategy(aiChatInput, responder, null)));
    }

    // build vendor api headers
    protected Map<String, String> buildHeaders() {
        return Map.of(
                "Content-Type", "application/json",
                "Authorization", "Bearer " + getChatKey()
        );
    }


    // get vendor api url
    protected abstract String getChatUrl();
    //get vendor api key
    protected abstract String getChatKey();
    //get vendor api listener strategy
    protected abstract ListenerStrategy getListenerStrategy(AiChatInput aiChatInput, Consumer<AiChatOutput> responder, BiConsumer<AiChatInput, AiChatOutput> callback);
    //convert AiChatInput to vendor api request
    protected abstract I convertRequest(AiChatInput aiChatInput);
    //convert vendor api response to AiChatOutput
    protected abstract AiChatOutput convertResponse(O response);
    //return vendor api response class type
    protected abstract Class<O> responseType();
}
