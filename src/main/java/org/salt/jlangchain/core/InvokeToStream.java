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

package org.salt.jlangchain.core;

import lombok.extern.slf4j.Slf4j;
import org.salt.function.flow.thread.TheadHelper;
import org.salt.jlangchain.core.message.BaseMessage;
import org.salt.jlangchain.core.parser.generation.ChatGeneration;
import org.salt.jlangchain.core.parser.generation.ChatGenerationChunk;
import org.salt.jlangchain.utils.SpringContextUtil;

@Slf4j
public class InvokeToStream extends BaseRunnable<Object, Object> {
    @Override
    public Object invoke(Object input) {
        return input;
    }

    @Override
    public Object stream(Object input) {
        String content;
        if (input instanceof ChatGeneration chatGeneration) {
            content = chatGeneration.getText();
        } else if (input instanceof BaseMessage baseMessage) {
            content = baseMessage.getContent();
        } else if (input instanceof String str) {
            content = str;
        } else {
            throw new RuntimeException("input must be ChatGeneration or BaseMessage");
        }

        ChatGenerationChunk chunk = new ChatGenerationChunk();
        SpringContextUtil.getApplicationContext().getBean(TheadHelper.class).submit(() -> {
            log.debug("do content: {}", content);
            for (int i = 0; i < content.length(); i++) {
                try {
                    ChatGenerationChunk text = new ChatGenerationChunk();
                    text.setText(String.valueOf(content.charAt(i)));
                    text.setLast(i == content.length() - 1);
                    log.debug("do content append: {}", text.toJson());
                    chunk.getIterator().append(text);
                } catch (Exception e) {
                    log.error("error: {}", e.getMessage(), e);
                }
            }
        });
        return chunk;
    }
}
