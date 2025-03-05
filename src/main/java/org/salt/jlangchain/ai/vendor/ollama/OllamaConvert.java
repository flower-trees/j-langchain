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

import org.salt.jlangchain.ai.common.enums.AiChatCode;
import org.salt.jlangchain.ai.common.enums.MessageType;
import org.salt.jlangchain.ai.common.param.AiChatInput;
import org.salt.jlangchain.ai.common.param.AiChatOutput;
import org.salt.jlangchain.ai.vendor.ollama.param.OllamaRequest;
import org.salt.jlangchain.ai.vendor.ollama.param.OllamaResponse;
import org.springframework.util.CollectionUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

public class OllamaConvert {

    protected static OllamaRequest convertRequest(AiChatInput aiChatInput) {
        OllamaRequest request = new OllamaRequest();
        request.setModel(aiChatInput.getModel());
        request.setStream(aiChatInput.isStream());

        if (!CollectionUtils.isEmpty(aiChatInput.getMessages())) {
            List<OllamaRequest.Message> doubaoMessages = aiChatInput.getMessages().stream()
                    .map(OllamaConvert::convertMessage)
                    .collect(Collectors.toList());
            request.setMessages(doubaoMessages);
        }
        OllamaRequest.Options options = new OllamaRequest.Options();
        options.setTemperature(0.8f);
        request.setOptions(options);

        request.setInput(aiChatInput.getInput());

        return request;
    }

    private static OllamaRequest.Message convertMessage(AiChatInput.Message aiChatMessage) {
        OllamaRequest.Message message = new OllamaRequest.Message();
        message.setRole(aiChatMessage.getRole());
        message.setContent(aiChatMessage.getContent());
        return message;
    }

    public static AiChatOutput convertResponse(OllamaResponse response) {
        AiChatOutput aiChatOutput = new AiChatOutput();
        List<AiChatOutput.Message> messages = getMessages(response);
        aiChatOutput.setMessages(messages);
        List<AiChatOutput.DataObject> data = getData(response);
        aiChatOutput.setData(data);
        if (response.isDone()) {
            aiChatOutput.setCode(AiChatCode.STOP.getCode());
        } else {
            aiChatOutput.setCode(AiChatCode.MESSAGE.getCode());
        }

        return aiChatOutput;
    }

    private static List<AiChatOutput.Message> getMessages(OllamaResponse response) {
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

    public static List<AiChatOutput.DataObject> getData(OllamaResponse response) {
        if (response.getData() != null) {
            return response.getData().stream().map(dataObject -> {
                AiChatOutput.DataObject data = new AiChatOutput.DataObject();
                data.setEmbedding(dataObject.getEmbedding());
                data.setIndex(dataObject.getIndex());
                data.setObject(dataObject.getObject());
                return data;
            }).toList();
        }
        return List.of();
    }
}
