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

import org.jetbrains.annotations.NotNull;
import org.salt.jlangchain.ai.vendor.aliyun.param.AliyunResponse;
import org.salt.jlangchain.ai.strategy.DoListener;
import org.salt.jlangchain.ai.common.param.AiChatInput;
import org.salt.jlangchain.ai.common.enums.AiChatCode;
import org.salt.jlangchain.core.message.MessageType;
import org.salt.jlangchain.ai.common.param.AiChatOutput;
import org.salt.jlangchain.utils.JsonUtil;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

public class AliyunListener extends DoListener  {

    public AliyunListener(AiChatInput aiChatInput, Consumer<AiChatOutput> responder, BiConsumer<AiChatInput, AiChatOutput> callback) {
        super(aiChatInput, responder, callback);
    }

    @Override
    protected AiChatOutput convertMsg(String msg) {
        AliyunResponse response = JsonUtil.fromJson(msg, AliyunResponse.class);
        if (response != null) {
            AiChatOutput aiChatOutput = new AiChatOutput();
            List<AiChatOutput.Message> messages = getMessages(response);
            aiChatOutput.setMessages(messages);

            aiChatOutput.setCode(AiChatCode.MESSAGE.getCode());

            return aiChatOutput;
        }
        return null;
    }

    private static @NotNull List<AiChatOutput.Message> getMessages(AliyunResponse response) {
        List<AiChatOutput.Message> messages = new ArrayList<>();
        if (response.getOutput() != null) {
            AiChatOutput.Message message = new AiChatOutput.Message();
            message.setRole(MessageType.AI.getCode());
            message.setContent(response.getOutput().getText());
            message.setType(org.salt.jlangchain.ai.common.enums.MessageType.MARKDOWN.getCode());
            messages.add(message);
        }
        return messages;
    }
}
