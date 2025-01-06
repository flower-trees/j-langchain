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

import org.apache.commons.lang3.StringUtils;
import org.salt.function.flow.thread.TheadHelper;
import org.salt.jlangchain.core.common.Iterator;
import org.salt.jlangchain.core.message.AIMessageChunk;
import org.salt.jlangchain.core.message.FinishReasonType;
import org.salt.jlangchain.core.parser.generation.ChatGenerationChunk;
import org.salt.jlangchain.utils.SpringContextUtil;

import java.util.List;
import java.util.concurrent.TimeoutException;

public abstract class BaseCumulativeTransformOutputParser extends BaseTransformOutputParser {

    protected void transformAsync(Object input, Iterator<?> iterator, ChatGenerationChunk rusult) {
        SpringContextUtil.getApplicationContext().getBean(TheadHelper.class).submit(
            () -> {
                    eventAction.eventStart(input, getRunId(), config);
                    while (iterator.hasNext()) {
                        try {
                            Object chunk = iterator.next();
                            if (chunk instanceof AIMessageChunk aiMessageChunk) {
                                ChatGenerationChunk chatGenerationChunk = new ChatGenerationChunk(aiMessageChunk);
                                cumulate(rusult, chatGenerationChunk);
                            } else if (chunk instanceof ChatGenerationChunk chatGenerationChunk) {
                                cumulate(rusult, chatGenerationChunk);
                            } else {
                                throw new RuntimeException("Unsupported message type: " + chunk.getClass().getName());
                            }
                        } catch (TimeoutException e) {
                            throw new RuntimeException(e);
                        }
                    }
                    eventAction.eventEnd(rusult, getRunId(), config);
                }
        );
    }

    private void cumulate(ChatGenerationChunk rusult, ChatGenerationChunk chatGenerationChunk) throws TimeoutException {
        rusult.add(chatGenerationChunk);
        chatGenerationChunk.setText(rusult.getCumulate().toString());
        if (chatGenerationChunk.getMessage() != null) {
            chatGenerationChunk.getMessage().setContent(rusult.getCumulate().toString());
        }
        ChatGenerationChunk resultChunk = (ChatGenerationChunk) parseResult(List.of(chatGenerationChunk));
        if (StringUtils.isNotEmpty(resultChunk.getText()) || FinishReasonType.STOP.equalsV(resultChunk.getMessage().getFinishReason())) {
            rusult.getIterator().append(resultChunk);
        }
        eventAction.eventStream(resultChunk, getRunId(), config);
    }
}
