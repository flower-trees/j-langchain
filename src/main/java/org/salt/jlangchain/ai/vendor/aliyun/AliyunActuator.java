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

package org.salt.jlangchain.ai.vendor.aliyun;

import org.salt.jlangchain.ai.client.stream.HttpStreamClient;
import org.salt.jlangchain.ai.common.param.AiChatInput;
import org.salt.jlangchain.ai.common.param.AiChatOutput;
import org.salt.jlangchain.ai.strategy.AiChatActuator;
import org.salt.jlangchain.ai.vendor.aliyun.param.AliyunRequest;
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
public class AliyunActuator implements AiChatActuator {

    @Value("${models.aliyun.chat-url}")
    private String chatUrl;

    @Value("${models.aliyun.chat-key}")
    private String chatKey;

    @Autowired
    HttpStreamClient commonHttpClient;

    @Override
    public AiChatOutput stream(AiChatInput aiChatInput, Consumer<AiChatOutput> responder) {
        Map<String, String> headers = new HashMap<>();
        headers.put("Content-Type", "application/json");
        headers.put("Authorization", "Bearer " + chatKey);
        headers.put("X-DashScope-SSE", "enable");

        AliyunRequest aliyunRequest = convert(aiChatInput);

        AtomicReference<AiChatOutput> r = new AtomicReference<>();
        commonHttpClient.request(chatUrl, JsonUtil.toJson(aliyunRequest), headers, List.of(new AliyunListener(aiChatInput, responder, (aiChatDto1, aiChatResponse) -> { r.set(aiChatResponse); })));
        return r.get();
    }

    @Override
    public void astream(AiChatInput aiChatInput, Consumer<AiChatOutput> responder) {

    }

    public static AliyunRequest convert(AiChatInput aiChatInput) {
        AliyunRequest request = new AliyunRequest();
        request.setModel(aiChatInput.getModel());
        request.setInput(new AliyunRequest.Input());

        List<AliyunRequest.Message> messages = aiChatInput.getMessages().stream()
                .map(AliyunActuator::convertMessage)
                .collect(Collectors.toList());
        request.getInput().setMessages(messages);

        request.setParameters(new AliyunRequest.Parameters());
        request.getParameters().setIncrementalOutput(true);

        return request;
    }

    private static AliyunRequest.Message convertMessage(AiChatInput.Message aiChatMessage) {
        AliyunRequest.Message message = new AliyunRequest.Message();
        message.setRole(aiChatMessage.getRole());
        message.setContent(aiChatMessage.getContent());
        return message;
    }
}
