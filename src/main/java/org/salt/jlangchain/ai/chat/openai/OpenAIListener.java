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

import org.jetbrains.annotations.NotNull;
import org.salt.jlangchain.ai.chat.openai.param.OpenAIResponse;
import org.salt.jlangchain.ai.chat.strategy.DoListener;
import org.salt.jlangchain.ai.common.enums.AiChatCode;
import org.salt.jlangchain.ai.common.enums.MessageType;
import org.salt.jlangchain.ai.common.param.AiChatInput;
import org.salt.jlangchain.ai.common.param.AiChatOutput;
import org.salt.jlangchain.utils.JsonUtil;
import org.springframework.util.CollectionUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

public class OpenAIListener extends DoListener  {

    public OpenAIListener(AiChatInput aiChatInput, Consumer<AiChatOutput> responder, BiConsumer<AiChatInput, AiChatOutput> completeCallback) {
        super(aiChatInput, responder, completeCallback);
    }

    @Override
    protected AiChatOutput convertMsg(String msg) {
        OpenAIResponse response = JsonUtil.fromJson(msg, OpenAIResponse.class);
        if (response != null) {
            AiChatOutput aiChatOutput = new AiChatOutput();

            aiChatOutput.setId(response.getId());

            //get messages
            List<AiChatOutput.Message> messages = getMessages(response);
            aiChatOutput.setMessages(messages);

            //get finish reason
            if (!CollectionUtils.isEmpty(response.getChoices())
                    && response.getChoices().get(0).getFinishReason() != null
                    && response.getChoices().get(0).getFinishReason().equals(AiChatCode.STOP.getCode())) {
                aiChatOutput.setCode(AiChatCode.STOP.getCode());
            } else {
                aiChatOutput.setCode(AiChatCode.MESSAGE.getCode());
            }

            return aiChatOutput;
        }
        return null;
    }

    private static @NotNull List<AiChatOutput.Message> getMessages(OpenAIResponse response) {
        List<AiChatOutput.Message> messages = new ArrayList<>();
        if (!CollectionUtils.isEmpty(response.getChoices()) && response.getChoices().get(0).getDelta() != null) {
            AiChatOutput.Message message = new AiChatOutput.Message();
            OpenAIResponse.Choice.Delta delta = response.getChoices().get(0).getDelta();
            message.setRole(delta.getRole());
            message.setContent(delta.getContent());
            message.setType(MessageType.MARKDOWN.getCode());
            messages.add(message);
        }
        return messages;
    }
}
