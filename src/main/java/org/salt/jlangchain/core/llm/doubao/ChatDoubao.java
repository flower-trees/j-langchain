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
import org.salt.jlangchain.ai.common.param.AiChatInput;
import org.salt.jlangchain.ai.chat.strategy.AiChatActuator;
import org.salt.jlangchain.ai.vendor.doubao.DoubaoActuator;
import org.salt.jlangchain.core.llm.BaseChatModel;

import java.util.Map;

@Slf4j
@EqualsAndHashCode(callSuper = true)
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChatDoubao extends BaseChatModel {

    protected String vendor = "doubao";
    protected String modelType = "llm";
    protected String model = "ep-20240611104225-2d4ww";
    protected Float temperature = 0.7f;
    protected Map<String, Object> modelKwargs;

    @Override
    public void otherInformation(AiChatInput aiChatInput) {
        aiChatInput.setModel(model);
        aiChatInput.setTemperature(temperature);
    }

    public Class<? extends AiChatActuator> getActuator() {
        return DoubaoActuator.class;
    }
}
