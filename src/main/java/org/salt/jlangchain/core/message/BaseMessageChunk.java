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

package org.salt.jlangchain.core.message;

import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Data;
import lombok.Getter;
import org.apache.commons.lang3.StringUtils;
import org.salt.jlangchain.core.common.Iterator;
import org.salt.jlangchain.core.common.IteratorAction;

@Data
public abstract class BaseMessageChunk<T extends BaseMessage> extends BaseMessage implements IteratorAction<T> {

    @Getter
    @JsonIgnore
    protected Iterator<T> iterator = new Iterator<>(this::isLast);

    @JsonIgnore
    protected StringBuilder cumulate = new StringBuilder();

    private boolean isLast(T chunk) {
        return StringUtils.equals(chunk.getFinishReason(), FinishReasonType.STOP.getCode());
    }

    public boolean isLast() {
        return StringUtils.equals(this.getFinishReason(), FinishReasonType.STOP.getCode());
    }

    public BaseMessageChunk<T> add(T chunk) {
        this.cumulate.append(chunk.getContent());
        this.content = this.cumulate.toString();
        return this;
    }

    protected BaseMessageChunk(BaseMessageChunkBuilder<T, ?, ?> builder) {
        super(builder);
        this.iterator = builder.iterator != null ? builder.iterator : new Iterator<>(this::isLast);
        this.cumulate = builder.cumulate != null ? builder.cumulate : new StringBuilder();
    }

    protected BaseMessageChunk() {
    }

    public static abstract class BaseMessageChunkBuilder<T extends BaseMessage, C extends BaseMessageChunk<T>, B extends BaseMessageChunkBuilder<T, C, B>> extends BaseMessageBuilder<C, B> {
        private Iterator<T> iterator;
        private StringBuilder cumulate;

        @JsonIgnore
        public B iterator(Iterator<T> iterator) {
            this.iterator = iterator;
            return self();
        }

        @JsonIgnore
        public B cumulate(StringBuilder cumulate) {
            this.cumulate = cumulate;
            return self();
        }

        @Override
        public String toString() {
            return "BaseMessageChunk.BaseMessageChunkBuilder(super=" + super.toString() + ", iterator=" + this.iterator + ", cumulate=" + this.cumulate + ")";
        }
    }
}
