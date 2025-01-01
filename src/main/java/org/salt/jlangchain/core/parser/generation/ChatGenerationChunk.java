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

package org.salt.jlangchain.core.parser.generation;

import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Getter;
import lombok.Setter;
import org.salt.jlangchain.core.common.Iterator;
import org.salt.jlangchain.core.common.IteratorAction;
import org.salt.jlangchain.core.message.BaseMessageChunk;
import org.salt.jlangchain.utils.GroceryUtil;

@Setter
@Getter
public class ChatGenerationChunk extends ChatGeneration implements IteratorAction<ChatGenerationChunk> {

    @Getter
    @JsonIgnore
    protected Iterator<ChatGenerationChunk> iterator = new Iterator<>(this::isLast);
    protected boolean isLast = false;
    @JsonIgnore
    @Getter
    protected StringBuilder cumulate = new StringBuilder();
    @JsonIgnore
    protected String id = GroceryUtil.generateId();

    private boolean isLast(ChatGenerationChunk chunk) {
        return chunk.isLast();
    }

    public ChatGenerationChunk() {
        super(null);
    }

    public ChatGenerationChunk(BaseMessageChunk<?> message) {
        super(message);
        if (message != null) {
            this.isLast = message.isLast();
        }
    }

    public ChatGenerationChunk add(ChatGenerationChunk chunk) {
        this.cumulate.append(chunk.getText());
        this.text = this.cumulate.toString();
        return this;
    }
}
