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

package org.salt.jlangchain.core.tts.card;

import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Getter;
import lombok.Setter;
import org.apache.commons.collections.CollectionUtils;
import org.salt.jlangchain.core.common.Iterator;
import org.salt.jlangchain.core.common.IteratorAction;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

@Setter
@Getter
public class TtsCardChunk extends TtsCard implements IteratorAction<TtsCardChunk> {

    protected Integer index;

    @JsonIgnore
    protected StringBuilder cumulate = new StringBuilder();

    @Getter
    protected List<Consumer<String>> finallyCalls = new ArrayList<>();

    @JsonIgnore
    protected Iterator<TtsCardChunk> iterator = new Iterator<>(this::isLast);
    protected boolean isLast;

    private boolean isLast(TtsCardChunk chunk) {
        return chunk.isLast();
    }

    public TtsCardChunk(String text, boolean isLast) {
        super(text, null);
        this.isLast = isLast;
    }

    public TtsCardChunk(Integer index, String text, String url, boolean isAudio, boolean isLast) {
        super(text, url);
        this.index = index;
        this.isAudio = isAudio;
        this.isLast = isLast;
    }

    public TtsCardChunk add(TtsCardChunk chunk) {
        this.cumulate.append(chunk.getText());
        this.text = this.cumulate.toString();
        return this;
    }

    public void addFinallyCall(Consumer<String> call) {
        this.finallyCalls.add(call);
    }

    public void addFinallyCalls(List<Consumer<String>> calls) {
        this.finallyCalls.addAll(calls);
    }

    public List<Consumer<String>> cleanFinallyCalls() {
        List<Consumer<String>> calls = this.finallyCalls;
        this.finallyCalls = List.of();
        return calls;
    }

    private void invokeFinallyCall(TtsCardChunk chunk) {
        if (chunk.isLast() && CollectionUtils.isNotEmpty(finallyCalls)) {
            for (Consumer<String> call : finallyCalls) {
                call.accept(cumulate.toString());
            }
        }
    }

    public void append(TtsCardChunk chunk) throws TimeoutException {
        if (chunk.isLast() && CollectionUtils.isNotEmpty(finallyCalls)) {
            chunk.setLast(false);
            iterator.append(chunk);
            TtsCardChunk last = new TtsCardChunk("", true);
//            this.invokeCallbacks(last);
            this.invokeFinallyCall(last);
            iterator.append(last);
        } else {
            iterator.append(chunk);
        }
    }
}
