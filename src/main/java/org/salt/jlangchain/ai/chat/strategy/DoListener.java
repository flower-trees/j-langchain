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

package org.salt.jlangchain.ai.chat.strategy;

import org.apache.commons.lang3.StringUtils;
import org.salt.jlangchain.ai.common.param.AiChatInput;
import org.salt.jlangchain.core.message.MessageType;
import org.salt.jlangchain.ai.common.enums.AiChatCode;
import org.salt.jlangchain.ai.common.param.AiChatOutput;
import org.salt.jlangchain.utils.JsonUtil;
import org.springframework.util.CollectionUtils;

import java.util.ArrayList;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

public abstract class DoListener implements ListenerStrategy {

    protected AiChatInput aiChatInput;
    
    protected AiChatOutput response;
    protected StringBuilder msgCache = new StringBuilder();

    protected Consumer<AiChatOutput> responder;
    protected BiConsumer<AiChatInput, AiChatOutput> completeCallback;

    protected boolean result = true;
    protected Throwable throwable;

    public DoListener(AiChatInput aiChatInput, Consumer<AiChatOutput> responder, BiConsumer<AiChatInput, AiChatOutput> completeCallback) {
        this.responder = responder;
        this.completeCallback = completeCallback;
        this.aiChatInput = aiChatInput;
        this.response = initResponse();
    }

    @Override
    public void onMessage(String msg) {
        //Convert msg to AiChatResponse
        AiChatOutput aiChatOutput = convertMsg(msg);

        //Collect spliced streaming answers
        if (StringUtils.equals(aiChatOutput.getCode(), AiChatCode.STOP.getCode()) && !CollectionUtils.isEmpty(aiChatOutput.getMessages())) {
            AiChatOutput.Message message = aiChatOutput.getMessages().stream().filter(m -> org.salt.jlangchain.ai.common.enums.MessageType.MARKDOWN.equalsV(m.getType())).findAny().orElse(null);
            if (message != null && StringUtils.isNotBlank((CharSequence) message.getContent())) {
                msgCache.append(message.getContent());
            }
        }

        //Send AiChatResponse back
        responder.accept(aiChatOutput);
    }

    protected abstract AiChatOutput convertMsg(String msg);

    public void onError(Throwable throwable) {

        result = false;
        this.throwable = throwable;

        AiChatOutput aiChatOutput = new AiChatOutput();
        aiChatOutput.setCode(AiChatCode.ERROR.getCode());
        responder.accept(aiChatOutput);

        response.setCode(AiChatCode.ERROR.getCode());
    }

    public void onComplete() {

        String msg = msgCache.toString();
        if (!StringUtils.isNotEmpty(msg)) {
            AiChatOutput.Message message = new AiChatOutput.Message();
            message.setRole(MessageType.AI.getCode());
            message.setType(org.salt.jlangchain.ai.common.enums.MessageType.MARKDOWN.getCode());
            message.setContent(msg);
            response.getMessages().add(message);
        }

        if (completeCallback != null) {
            completeCallback.accept(aiChatInput, response);
        }
    }

    private AiChatOutput initResponse() {
        response = JsonUtil.convert(aiChatInput, AiChatOutput.class);
        response.setMessages(new ArrayList<>());
        response.setCode(AiChatCode.MESSAGE.getCode());
        return response;
    }
}
