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
import org.apache.commons.collections.CollectionUtils;
import org.salt.jlangchain.core.common.Iterator;
import org.salt.jlangchain.core.common.IteratorAction;
import org.salt.jlangchain.core.message.BaseMessageChunk;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

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

    protected List<Consumer<String>> callbacks = new ArrayList<>();

    @Getter
    protected List<Consumer<String>> finallyCalls = new ArrayList<>();

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

    public void addCallback(Consumer<String> callback) {
        callbacks.add(callback);
    }

    public void addFinallyCall(Consumer<String> finallyCall) {
        finallyCalls.add(finallyCall);
    }

    public List<Consumer<String>> cleanFinallyCalls() {
        List<Consumer<String>> finallyCalls = this.finallyCalls;
        this.finallyCalls = List.of();
        return finallyCalls;
    }

    private void invokeCallbacks(ChatGenerationChunk chunk) {
        if (chunk.isLast && CollectionUtils.isNotEmpty(callbacks)) {
            for (Consumer<String> callback : callbacks) {
                callback.accept(this.text);
            }
        }
    }

    private void invokeFinallyCall(ChatGenerationChunk chunk) {
        if (chunk.isLast && CollectionUtils.isNotEmpty(finallyCalls)) {
            for (Consumer<String> finallyCall : finallyCalls) {
                finallyCall.accept(this.text);
            }
        }
    }

    public ChatGenerationChunk append(ChatGenerationChunk chunk) throws TimeoutException {
        if (chunk.isLast && (CollectionUtils.isNotEmpty(callbacks) || CollectionUtils.isNotEmpty(finallyCalls))) {
            chunk.setLast(false);
            iterator.append(chunk);
            ChatGenerationChunk last = new ChatGenerationChunk();
            last.setText("");
            last.setLast(true);
            this.invokeCallbacks(last);
            this.invokeFinallyCall(last);
            iterator.append(last);
        } else {
            iterator.append(chunk);
        }
        return this;
    }
}
