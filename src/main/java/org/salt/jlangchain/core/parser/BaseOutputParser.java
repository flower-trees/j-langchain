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

package org.salt.jlangchain.core.parser;

import lombok.Data;
import lombok.EqualsAndHashCode;
import org.salt.function.flow.context.ContextBus;
import org.salt.jlangchain.core.common.CallInfo;
import org.salt.jlangchain.core.event.EventMessageChunk;
import org.salt.jlangchain.core.message.BaseMessage;
import org.salt.jlangchain.core.parser.generation.ChatGeneration;
import org.salt.jlangchain.core.parser.generation.ChatGenerationChunk;
import org.salt.jlangchain.core.parser.generation.Generation;

import java.util.List;

@EqualsAndHashCode(callSuper = true)
@Data
public abstract class BaseOutputParser extends BaseLLMOutputParser<Generation> {

    @Override
    public Generation invoke(Object input) {
        if (input instanceof String stringPrompt){
            return parseResult(List.of(new Generation(stringPrompt)));
        } else if (input instanceof BaseMessage baseMessage){
            return parseResult(List.of(new ChatGeneration(baseMessage)));
        } else {
            throw new RuntimeException("Unsupported input type: " + input.getClass().getName());
        }
    }

    @Override
    public ChatGenerationChunk stream(Object input) {
        return transform(input);
    }

    @Override
    public EventMessageChunk streamEvent(Object input) {
        EventMessageChunk eventMessageChunk = new EventMessageChunk();

        ContextBus.create(input);
        getContextBus().putTransmit(CallInfo.EVENT.name(), true);
        getContextBus().putTransmit(CallInfo.EVENT_CHAIN.name(), false);
        getContextBus().putTransmit(CallInfo.EVENT_MESSAGE_CHUNK.name(), eventMessageChunk);

        ChatGenerationChunk chunk = stream(input);
        chunk.ignore();

        return eventMessageChunk;
    }

    @Override
    protected Generation parseResult(List<Generation> result) {
        return parse(result.get(0));
    }

    protected abstract Generation parse(Generation text);

    protected abstract ChatGenerationChunk transform(Object input);
}
