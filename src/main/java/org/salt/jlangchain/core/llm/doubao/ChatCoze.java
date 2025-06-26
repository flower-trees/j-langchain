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

package org.salt.jlangchain.core.llm.doubao;

import lombok.*;
import lombok.extern.slf4j.Slf4j;
import org.salt.jlangchain.ai.chat.strategy.AiChatActuator;
import org.salt.jlangchain.ai.common.param.AiChatInput;
import org.salt.jlangchain.ai.vendor.doubao.coze.CozeActuator;
import org.salt.jlangchain.core.llm.BaseChatModel;

import java.util.Map;

@Slf4j
@EqualsAndHashCode(callSuper = true)
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChatCoze extends BaseChatModel {

    @Builder.Default
    protected String vendor = "doubao";
    @Builder.Default
    protected String botId = "751971414224112XXXX";
    protected Map<String, Object> modelKwargs;
    @Builder.Default
    protected String userId = "123";
    protected String key;

    @Override
    public void otherInformation(AiChatInput aiChatInput) {
        aiChatInput.setBotId(botId);
        aiChatInput.setUserId(userId);
        aiChatInput.setKey(key);
    }

    public Class<? extends AiChatActuator> getActuator() {
        return CozeActuator.class;
    }
}
