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
import org.salt.jlangchain.ai.chat.strategy.BaseAiChatActuator;
import org.salt.jlangchain.ai.chat.strategy.ListenerStrategy;
import org.salt.jlangchain.ai.client.stream.HttpStreamClient;
import org.salt.jlangchain.ai.common.param.AiChatInput;
import org.salt.jlangchain.ai.common.param.AiChatOutput;

import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.stream.Collectors;

public abstract class OpenAIActuator extends BaseAiChatActuator<OpenAIRequest> {

    public OpenAIActuator(HttpStreamClient commonHttpClient) {
        super(commonHttpClient);
    }

    @Override
    protected ListenerStrategy getListenerStrategy(AiChatInput aiChatInput, Consumer<AiChatOutput> responder, BiConsumer<AiChatInput, AiChatOutput> callback) {
        return new OpenAIListener(aiChatInput, responder, callback);
    }

    @Override
    public OpenAIRequest convert(AiChatInput aiChatInput) {
        OpenAIRequest openAIRequest = new OpenAIRequest();
        openAIRequest.setModel(aiChatInput.getModel());
        openAIRequest.setStream(aiChatInput.isStream());

        List<OpenAIRequest.Message> chatGPTMessages = aiChatInput.getMessages().stream()
                .map(OpenAIActuator::convertMessage)
                .collect(Collectors.toList());
        openAIRequest.setMessages(chatGPTMessages);

        return openAIRequest;
    }

    private static OpenAIRequest.Message convertMessage(AiChatInput.Message aiChatMessage) {
        OpenAIRequest.Message chatGPTMessage = new OpenAIRequest.Message();
        chatGPTMessage.setRole(aiChatMessage.getRole());
        chatGPTMessage.setContent(aiChatMessage.getContent());
        return chatGPTMessage;
    }
}
