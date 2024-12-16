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

package org.salt.jlangchain.ai.vendor.moonshot;

import org.jetbrains.annotations.NotNull;
import org.salt.jlangchain.ai.vendor.moonshot.param.MoonshotResponse;
import org.salt.jlangchain.ai.strategy.DoListener;
import org.salt.jlangchain.ai.common.param.AiChatInput;
import org.salt.jlangchain.ai.common.enums.AiChatCode;
import org.salt.jlangchain.ai.common.enums.MessageType;
import org.salt.jlangchain.ai.common.param.AiChatOutput;
import org.salt.jlangchain.utils.JsonUtil;
import org.springframework.util.CollectionUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

public class MoonshotListener extends DoListener {

    public MoonshotListener(AiChatInput aiChatInput, Consumer<AiChatOutput> responder, BiConsumer<AiChatInput, AiChatOutput> callback) {
        super(aiChatInput, responder, callback);
    }

    @Override
    protected AiChatOutput convertMsg(String msg) {
        MoonshotResponse response = JsonUtil.fromJson(msg, MoonshotResponse.class);
        if (response != null) {
            AiChatOutput aiChatOutput = new AiChatOutput();
            List<AiChatOutput.Message> messages = getMessages(response);
            aiChatOutput.setMessages(messages);

            aiChatOutput.setCode(AiChatCode.MESSAGE.getCode());

            return aiChatOutput;
        }
        return null;
    }

    private static @NotNull List<AiChatOutput.Message> getMessages(MoonshotResponse response) {
        List<AiChatOutput.Message> messages = new ArrayList<>();
        if (!CollectionUtils.isEmpty(response.getChoices()) && response.getChoices().get(0).getDelta() != null) {
            AiChatOutput.Message message = new AiChatOutput.Message();
            MoonshotResponse.Choice.Delta delta = response.getChoices().get(0).getDelta();
            message.setRole(delta.getRole());
            message.setContent(delta.getContent());
            message.setType(MessageType.MARKDOWN.getCode());
            messages.add(message);
        }
        return messages;
    }
}
