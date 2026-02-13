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
import org.salt.jlangchain.core.common.Iterator;
import org.salt.jlangchain.core.common.IteratorAction;

@Setter
@Getter
public class TtsCardChunk extends TtsCard implements IteratorAction<TtsCardChunk> {

    protected Integer index;

    @JsonIgnore
    protected StringBuilder cumulate = new StringBuilder();

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
}
