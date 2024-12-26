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

import org.salt.jlangchain.ai.chat.openai.param.OpenAIResponse;
import org.salt.jlangchain.ai.chat.strategy.DoListener;
import org.salt.jlangchain.ai.common.param.AiChatInput;
import org.salt.jlangchain.ai.common.param.AiChatOutput;
import org.salt.jlangchain.utils.JsonUtil;

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
            return OpenAIConver.convertResponse(response);
        }
        return null;
    }
}
