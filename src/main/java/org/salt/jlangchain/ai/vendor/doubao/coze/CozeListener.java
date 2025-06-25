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

package org.salt.jlangchain.ai.vendor.doubao.coze;

import lombok.extern.slf4j.Slf4j;
import org.salt.jlangchain.ai.chat.openai.RoleType;
import org.salt.jlangchain.ai.chat.sse.SseDoListener;
import org.salt.jlangchain.ai.common.enums.AiChatCode;
import org.salt.jlangchain.ai.common.enums.MessageType;
import org.salt.jlangchain.ai.common.param.AiChatInput;
import org.salt.jlangchain.ai.common.param.AiChatOutput;
import org.salt.jlangchain.ai.vendor.doubao.coze.dto.MessageDeltaEvent;
import org.salt.jlangchain.utils.JsonUtil;

import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

@Slf4j
public class CozeListener extends SseDoListener {

    public CozeListener(AiChatInput aiChatInput, Consumer<AiChatOutput> responder, BiConsumer<AiChatInput, AiChatOutput> completeCallback) {
        super(aiChatInput, responder, completeCallback);
    }

    @Override
    protected AiChatOutput convertMsg(String event, String msg) {
        if (event.equals(SseEventType.MESSAGE_DELTA.getCode())) {
            MessageDeltaEvent deltaEvent = JsonUtil.fromJson(msg, MessageDeltaEvent.class);
            if (deltaEvent == null) {
                log.warn("convertMsg deltaEvent fromJson fail: {}", deltaEvent);
                return null;
            }
            AiChatOutput aiChatOutput = new AiChatOutput();

            aiChatOutput.setId(response.getId());

            //set messages
            AiChatOutput.Message message = new AiChatOutput.Message();
            message.setRole(RoleType.ASSISTANT.getCode());
            message.setContent(deltaEvent.getContent());
            message.setType(MessageType.MARKDOWN.getCode());
            aiChatOutput.setMessages(List.of(message));

            return aiChatOutput;
        } else if (event.equals(SseEventType.CHAT_COMPLETED.getCode()) || event.equals("stop")) {
            AiChatOutput aiChatOutput = new AiChatOutput();
            aiChatOutput.setCode(AiChatCode.STOP.getCode());
            AiChatOutput.Message message = new AiChatOutput.Message();
            message.setRole(RoleType.ASSISTANT.getCode());
            message.setContent("");
            message.setType(MessageType.MARKDOWN.getCode());
            aiChatOutput.setMessages(List.of(message));
            return aiChatOutput;
        }
        return null;
    }
}