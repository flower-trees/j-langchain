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

import org.jetbrains.annotations.NotNull;
import org.salt.jlangchain.ai.common.enums.AiChatCode;
import org.salt.jlangchain.ai.common.enums.MessageType;
import org.salt.jlangchain.ai.common.param.AiChatInput;
import org.salt.jlangchain.ai.common.param.AiChatOutput;
import org.salt.jlangchain.ai.chat.strategy.DoListener;
import org.salt.jlangchain.ai.vendor.ollama.param.OllamaResponse;
import org.salt.jlangchain.utils.JsonUtil;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

public class OllamaListener extends DoListener {

    public OllamaListener(AiChatInput aiChatInput, Consumer<AiChatOutput> responder, BiConsumer<AiChatInput, AiChatOutput> callback) {
        super(aiChatInput, responder, callback);
    }

    @Override
    protected AiChatOutput convertMsg(String msg) {
        OllamaResponse response = JsonUtil.fromJson(msg, OllamaResponse.class);
        if (response != null) {
            AiChatOutput aiChatOutput = new AiChatOutput();
            List<AiChatOutput.Message> messages = getMessages(response);
            aiChatOutput.setMessages(messages);

            if (response.isDone()) {
                aiChatOutput.setCode(AiChatCode.STOP.getCode());
            } else {
                aiChatOutput.setCode(AiChatCode.MESSAGE.getCode());
            }

            return aiChatOutput;
        }
        return null;
    }

    private static @NotNull List<AiChatOutput.Message> getMessages(OllamaResponse response) {
        List<AiChatOutput.Message> messages = new ArrayList<>();
        if (response.getMessage() != null) {
            AiChatOutput.Message message = new AiChatOutput.Message();
            OllamaResponse.Message responseMessage = response.getMessage();
            message.setRole(responseMessage.getRole());
            message.setContent(responseMessage.getContent());
            message.setType(MessageType.MARKDOWN.getCode());
            messages.add(message);
        }
        return messages;
    }
}
