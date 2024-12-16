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

import org.salt.jlangchain.core.message.AIMessageChunk;
import org.salt.jlangchain.core.message.BaseMessage;
import org.salt.jlangchain.core.message.BaseMessageChunk;
import org.salt.jlangchain.core.message.FinishReasonType;
import org.salt.jlangchain.core.parser.generation.ChatGenerationChunk;
import org.salt.jlangchain.utils.SpringContextUtil;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.List;
import java.util.concurrent.TimeoutException;

public abstract class BaseTransformOutputParser extends BaseOutputParser {

    @Override
    protected ChatGenerationChunk transform(Object input) {
        if (input instanceof String stringPrompt) {
            AIMessageChunk aiMessageChunk = new AIMessageChunk();
            buildAsync(aiMessageChunk, stringPrompt);
            ChatGenerationChunk result = new ChatGenerationChunk(null);
            transformAsync(aiMessageChunk, result);
            return result;
        } else if (input instanceof BaseMessageChunk<? extends BaseMessage> baseMessageChunk){
            if (baseMessageChunk instanceof AIMessageChunk){
                ChatGenerationChunk result = new ChatGenerationChunk(null);
                transformAsync(baseMessageChunk, result);
                return result;
            } else {
                throw new RuntimeException("Unsupported message type: " + baseMessageChunk.getClass().getName());
            }
        } else {
            throw new RuntimeException("Unsupported input type: " + input.getClass().getName());
        }
    }

    private void transformAsync(BaseMessageChunk<? extends BaseMessage> baseMessageChunk, ChatGenerationChunk rusult) {
        SpringContextUtil.getApplicationContext().getBean(ThreadPoolTaskExecutor.class).execute(
                () -> {
                    while (baseMessageChunk.getIterator().hasNext()) {
                        try {
                            BaseMessage chunk = baseMessageChunk.getIterator().next();
                            if (chunk instanceof AIMessageChunk aiMessageChunk){
                                ChatGenerationChunk chatGenerationChunk = (ChatGenerationChunk) parseResult(List.of(new ChatGenerationChunk(aiMessageChunk)));
                                rusult.getIterator().append(chatGenerationChunk);
                            } else {
                                throw new RuntimeException("Unsupported message type: " + chunk.getClass().getName());
                            }
                        } catch (TimeoutException e) {
                            throw new RuntimeException(e);
                        }
                    }
                }
        );
    }

    public void buildAsync(BaseMessageChunk<AIMessageChunk> baseMessageChunk, String stringPrompt) {
        SpringContextUtil.getApplicationContext().getBean(ThreadPoolTaskExecutor.class).execute(
                () -> {
                    AIMessageChunk aiMessageChunk = AIMessageChunk.builder().content(stringPrompt).finishReason(FinishReasonType.STOP.getCode()).build();
                    try {
                        baseMessageChunk.getIterator().append(aiMessageChunk);
                    } catch (TimeoutException e) {
                        throw new RuntimeException(e);
                    }
                }
        );
    }
}
