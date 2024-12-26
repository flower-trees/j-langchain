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

package org.salt.jlangchain.core.llm;

import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.salt.jlangchain.ai.common.enums.AiChatCode;
import org.salt.jlangchain.ai.chat.openai.RoleType;
import org.salt.jlangchain.ai.common.param.AiChatInput;
import org.salt.jlangchain.ai.common.param.AiChatOutput;
import org.salt.jlangchain.ai.chat.strategy.AiChatActuator;
import org.salt.jlangchain.core.BaseRunnable;
import org.salt.jlangchain.core.message.*;
import org.salt.jlangchain.core.prompt.value.ChatPromptValue;
import org.salt.jlangchain.core.prompt.value.StringPromptValue;
import org.salt.jlangchain.utils.JsonUtil;
import org.salt.jlangchain.utils.SpringContextUtil;
import org.springframework.util.CollectionUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

@Slf4j
@EqualsAndHashCode(callSuper = true)
@Data
public abstract class BaseChatModel extends BaseRunnable<BaseMessage, Object> {

    @Override
    public AIMessage invoke(Object input) {

        List<AiChatInput.Message> messages = convertMessage(input);
        AiChatInput aiChatInput = AiChatInput.builder().messages(messages).stream(false).build();

        otherInformation(aiChatInput);

        AiChatOutput aiChatOutput = SpringContextUtil.getApplicationContext().getBean(getActuator()).invoke(aiChatInput);

        if (CollectionUtils.isEmpty(aiChatOutput.getMessages())) {
            return AIMessage.builder().content("").build();
        }
        return AIMessage.builder().content((String) aiChatOutput.getMessages().get(0).getContent()).build();
    }

    @Override
    public AIMessageChunk stream(Object input) {

        AIMessageChunk aiMessageChunk = new AIMessageChunk();
        List<AiChatInput.Message> messages = convertMessage(input);
        AiChatInput aiChatInput = AiChatInput.builder().messages(messages).stream(true).build();

        otherInformation(aiChatInput);

        Consumer<AiChatOutput> consumer = getConsumer(aiMessageChunk);
        SpringContextUtil.getApplicationContext().getBean(getActuator()).astream(aiChatInput, consumer);

        return aiMessageChunk;
    }

    public abstract void otherInformation(AiChatInput aiChatInput);
    public abstract Class<? extends AiChatActuator> getActuator();

    protected List<AiChatInput.Message> convertMessage(Object input) {
        if (input instanceof StringPromptValue stringPromptValue) {
            return List.of(new AiChatInput.Message(RoleType.USER.getCode(), stringPromptValue.getText()));
        } else if(input instanceof ChatPromptValue chatPromptValue) {
            if (CollectionUtils.isEmpty(chatPromptValue.getMessages())) {
                throw new RuntimeException("chatPromptValue.getMessages() is empty");
            }
            List<AiChatInput.Message> messages = new ArrayList<>();
            for (BaseMessage baseMessage : chatPromptValue.getMessages()) {
                switch (MessageType.fromCode(baseMessage.getRole())) {
                    case HUMAN:
                        messages.add(new AiChatInput.Message(RoleType.USER.getCode(), baseMessage.getContent()));
                        break;
                    case AI:
                        messages.add(new AiChatInput.Message(RoleType.ASSISTANT.getCode(), baseMessage.getContent()));
                        break;
                    case SYSTEM:
                        messages.add(new AiChatInput.Message(RoleType.SYSTEM.getCode(), baseMessage.getContent()));
                }
            }
            return messages;
        } if (input instanceof String stringPrompt) {
            return List.of(new AiChatInput.Message(RoleType.USER.getCode(), stringPrompt));
        } else {
            throw new RuntimeException("input must be StringPromptValue or ChatPromptValue");
        }
    }

    protected Consumer<AiChatOutput> getConsumer(AIMessageChunk aiMessageChunk) {
        return (aiChatOutput) -> {
            log.debug("aiChatOutput: {}", JsonUtil.toJson(aiChatOutput));

            AIMessageChunk chunk = AIMessageChunk.builder().id(aiChatOutput.getId()).build();

            if (!CollectionUtils.isEmpty(aiChatOutput.getMessages())) {
                chunk.setContent((String) aiChatOutput.getMessages().get(0).getContent());
            }

            if (StringUtils.equals(aiChatOutput.getCode(), AiChatCode.STOP.getCode())) {
                chunk.setFinishReason(FinishReasonType.STOP.getCode());
            }
            try {
                aiMessageChunk.getIterator().append(chunk);
            } catch (TimeoutException e) {
                throw new RuntimeException(e);
            }
        };
    }

}
