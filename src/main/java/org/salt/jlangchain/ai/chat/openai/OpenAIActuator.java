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

package org.salt.jlangchain.ai.chat.openai;

import org.salt.jlangchain.ai.chat.openai.param.OpenAIRequest;
import org.salt.jlangchain.ai.chat.openai.param.OpenAIResponse;
import org.salt.jlangchain.ai.chat.strategy.BaseAiChatActuator;
import org.salt.jlangchain.ai.chat.strategy.ListenerStrategy;
import org.salt.jlangchain.ai.client.stream.HttpStreamClient;
import org.salt.jlangchain.ai.common.param.AiChatInput;
import org.salt.jlangchain.ai.common.param.AiChatOutput;

import java.util.function.BiConsumer;
import java.util.function.Consumer;

public abstract class OpenAIActuator extends BaseAiChatActuator<OpenAIResponse, OpenAIRequest> {

    public OpenAIActuator(HttpStreamClient commonHttpClient) {
        super(commonHttpClient);
    }

    @Override
    protected ListenerStrategy getListenerStrategy(AiChatInput aiChatInput, Consumer<AiChatOutput> responder, BiConsumer<AiChatInput, AiChatOutput> callback) {
        return new OpenAIListener(aiChatInput, responder, callback);
    }

    @Override
    protected OpenAIRequest convertRequest(AiChatInput aiChatInput) {
        return OpenAIConver.convertRequest(aiChatInput);
    }

    @Override
    protected AiChatOutput convertResponse(OpenAIResponse response) {
        return OpenAIConver.convertResponse(response);
    }

    protected Class<OpenAIResponse> responseType() {
        return OpenAIResponse.class;
    }
}
