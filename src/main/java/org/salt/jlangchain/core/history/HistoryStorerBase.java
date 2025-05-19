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

package org.salt.jlangchain.core.history;

import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.extern.slf4j.Slf4j;
import org.salt.jlangchain.core.common.CallInfo;
import org.salt.jlangchain.core.message.BaseMessage;
import org.salt.jlangchain.core.message.MessageType;
import org.salt.jlangchain.core.parser.generation.ChatGenerationChunk;
import org.salt.jlangchain.core.parser.generation.Generation;

import java.util.ArrayList;
import java.util.List;

@Slf4j
@EqualsAndHashCode(callSuper = true)
@Data
public abstract class HistoryStorerBase extends HistoryBase {

    @Override
    public Object invoke(Object input) {

        if (input instanceof Generation generation) {
            List<BaseMessage> messages = new ArrayList<>();
            messages.add(BaseMessage.fromMessage(MessageType.HUMAN.getCode(), getContextBus().getTransmit(CallInfo.QUESTION.name())));
            messages.add(BaseMessage.fromMessage(MessageType.AI.getCode(), generation.getText()));
            HistoryInfos historyInfos = HistoryInfos.builder().messages(messages).build();
            storeHistory(historyInfos);
        } if (input instanceof ChatGenerationChunk chatGenerationChunk) {
            chatGenerationChunk.addCallback(text -> {
                List<BaseMessage> messages = new ArrayList<>();
                messages.add(BaseMessage.fromMessage(MessageType.HUMAN.getCode(), getContextBus().getTransmit(CallInfo.QUESTION.name())));
                messages.add(BaseMessage.fromMessage(MessageType.AI.getCode(), text));
                HistoryInfos historyInfos = HistoryInfos.builder().messages(messages).build();
                storeHistory(historyInfos);
            });
        } else {
            throw new RuntimeException("input must be StringPromptValue or ChatPromptValue or String");
        }

        return input;
    }

    public abstract void storeHistory(HistoryInfos historyInfos);
}
