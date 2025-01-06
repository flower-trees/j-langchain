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

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.salt.function.flow.thread.TheadHelper;
import org.salt.jlangchain.core.common.Iterator;
import org.salt.jlangchain.core.event.EventAction;
import org.salt.jlangchain.core.message.AIMessageChunk;
import org.salt.jlangchain.core.message.BaseMessage;
import org.salt.jlangchain.core.message.BaseMessageChunk;
import org.salt.jlangchain.core.message.FinishReasonType;
import org.salt.jlangchain.core.parser.generation.ChatGenerationChunk;
import org.salt.jlangchain.utils.JsonUtil;
import org.salt.jlangchain.utils.SpringContextUtil;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeoutException;

@Slf4j
public abstract class BaseTransformOutputParser extends BaseOutputParser {

    Map<String, Object> config = Map.of(
            "run_name", this.getClass().getSimpleName(),
            "tags", List.of()
    );
    EventAction eventAction = new EventAction("parser");

    @Override
    protected ChatGenerationChunk transform(Object input) {
        if (input instanceof String stringPrompt) {
            BaseMessageChunk<AIMessageChunk> aiMessageChunk = buildAsync(stringPrompt);
            ChatGenerationChunk result = new ChatGenerationChunk();
            transformAsync(input, aiMessageChunk.getIterator(), result);
            return result;
        } else if (input instanceof BaseMessageChunk<? extends BaseMessage> baseMessageChunk){
            if (baseMessageChunk instanceof AIMessageChunk){
                ChatGenerationChunk result = new ChatGenerationChunk();
                transformAsync(input, baseMessageChunk.getIterator(), result);
                return result;
            } else {
                throw new RuntimeException("Unsupported message type: " + baseMessageChunk.getClass().getName());
            }
        } else if (input instanceof ChatGenerationChunk chatGenerationChunk){
            ChatGenerationChunk result = new ChatGenerationChunk();
            transformAsync(input, chatGenerationChunk.getIterator(), result);
            return result;
        } else {
            throw new RuntimeException("Unsupported input type: " + input.getClass().getName());
        }
    }

    protected void transformAsync(Object input, Iterator<?> iterator, ChatGenerationChunk rusult) {
        SpringContextUtil.getApplicationContext().getBean(TheadHelper.class).submit(
            () -> {
                eventAction.eventStart(input, config);
                try {
                    while (iterator.hasNext()) {
                        Object chunk = iterator.next();
                        log.debug("chunk: {}", JsonUtil.toJson(chunk));
                        if (chunk instanceof AIMessageChunk aiMessageChunk) {
                            ChatGenerationChunk resultChunk = (ChatGenerationChunk) parseResult(List.of(new ChatGenerationChunk(aiMessageChunk)));
                            if (StringUtils.isNotEmpty(resultChunk.getText()) || resultChunk.isLast()) {
                                rusult.add(resultChunk);
                                rusult.getIterator().append(resultChunk);
                                eventAction.eventStream(resultChunk, config);
                            }
                        } else if (chunk instanceof ChatGenerationChunk chatGenerationChunk) {
                            ChatGenerationChunk resultChunk = (ChatGenerationChunk) parseResult(List.of(chatGenerationChunk));
                            if (StringUtils.isNotEmpty(resultChunk.getText()) || resultChunk.isLast()) {
                                rusult.add(resultChunk);
                                rusult.getIterator().append(resultChunk);
                                eventAction.eventStream(resultChunk, config);
                            }
                        } else {
                            throw new RuntimeException("Unsupported message type: " + chunk.getClass().getName());
                        }
                    }
                } catch (TimeoutException e) {
                    log.error("transformAsync timeout:", e);
                    throw new RuntimeException(e);
                }
                eventAction.eventEnd(rusult, config);
            }
        );
    }

    protected BaseMessageChunk<AIMessageChunk> buildAsync(String stringPrompt) {
        AIMessageChunk baseMessageChunk = new AIMessageChunk();
        baseMessageChunk.asynAppend(AIMessageChunk.builder().content(stringPrompt).finishReason(FinishReasonType.STOP.getCode()).build());
        return baseMessageChunk;
    }

    public BaseTransformOutputParser withConfig(Map<String, Object> config) {
        Map<String, Object> map = new HashMap<>(this.config);
        map.putAll(config);
        this.config = map;
        return this;
    }
}
