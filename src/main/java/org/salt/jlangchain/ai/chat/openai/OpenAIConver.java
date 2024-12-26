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
import org.salt.jlangchain.ai.common.enums.AiChatCode;
import org.salt.jlangchain.ai.common.enums.MessageType;
import org.salt.jlangchain.ai.common.param.AiChatInput;
import org.salt.jlangchain.ai.common.param.AiChatOutput;
import org.springframework.util.CollectionUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

public class OpenAIConver {

    protected static OpenAIRequest convertRequest(AiChatInput aiChatInput) {
        OpenAIRequest openAIRequest = new OpenAIRequest();
        openAIRequest.setModel(aiChatInput.getModel());
        openAIRequest.setStream(aiChatInput.isStream());

        List<OpenAIRequest.Message> chatGPTMessages = aiChatInput.getMessages().stream()
                .map(OpenAIConver::convertMessage)
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

    public static AiChatOutput convertResponse(OpenAIResponse response) {
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

    private static List<AiChatOutput.Message> getMessages(OpenAIResponse response) {
        List<AiChatOutput.Message> messages = new ArrayList<>();
        if (!CollectionUtils.isEmpty(response.getChoices()) && response.getChoices().get(0).getDelta() != null) {
            AiChatOutput.Message message = new AiChatOutput.Message();
            OpenAIResponse.Choice.Delta delta = response.getChoices().get(0).getDelta();
            message.setRole(delta.getRole());
            message.setContent(delta.getContent());
            message.setType(MessageType.MARKDOWN.getCode());
            messages.add(message);
        } else if (!CollectionUtils.isEmpty(response.getChoices()) && response.getChoices().get(0).getMessage()!= null) {
            AiChatOutput.Message message = new AiChatOutput.Message();
            OpenAIResponse.Choice.Delta chatGPTMessage = response.getChoices().get(0).getMessage();
            message.setRole(chatGPTMessage.getRole());
            message.setContent(chatGPTMessage.getContent());
            message.setType(MessageType.MARKDOWN.getCode());
            messages.add(message);
        }
        return messages;
    }
}
