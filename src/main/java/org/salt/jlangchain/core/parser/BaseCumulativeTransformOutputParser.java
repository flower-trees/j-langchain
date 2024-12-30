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
import org.salt.jlangchain.core.common.Iterator;
import org.salt.jlangchain.core.message.AIMessageChunk;
import org.salt.jlangchain.core.message.FinishReasonType;
import org.salt.jlangchain.core.parser.generation.ChatGenerationChunk;
import org.salt.jlangchain.utils.SpringContextUtil;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.List;
import java.util.concurrent.TimeoutException;

public abstract class BaseCumulativeTransformOutputParser extends BaseTransformOutputParser {

    protected void transformAsync(Iterator<?> iterator, ChatGenerationChunk rusult) {
        SpringContextUtil.getApplicationContext().getBean(ThreadPoolTaskExecutor.class).execute(
                () -> {
                    StringBuilder cumulate = new StringBuilder();
                    while (iterator.hasNext()) {
                        try {
                            Object chunk = iterator.next();
                            if (chunk instanceof AIMessageChunk aiMessageChunk) {
                                cumulate.append(aiMessageChunk.getContent());
                                aiMessageChunk.setContent(cumulate.toString());
                                ChatGenerationChunk resultChunk = (ChatGenerationChunk) parseResult(List.of(new ChatGenerationChunk(aiMessageChunk)));
                                if (StringUtils.isNotEmpty(resultChunk.getText()) || FinishReasonType.STOP.equalsV(resultChunk.getMessage().getFinishReason())) {
                                    rusult.getIterator().append(resultChunk);
                                }
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
}
