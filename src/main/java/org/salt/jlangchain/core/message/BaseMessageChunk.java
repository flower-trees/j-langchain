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
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;
import org.apache.commons.lang3.StringUtils;
import org.salt.jlangchain.core.common.Iterator;
import org.salt.jlangchain.core.common.IteratorAction;
import org.salt.jlangchain.utils.GroceryUtil;

@EqualsAndHashCode(callSuper = true)
@Data
@SuperBuilder
@NoArgsConstructor
public abstract class BaseMessageChunk<T extends BaseMessage> extends BaseMessage implements IteratorAction<T> {

    @Getter
    @JsonIgnore
    protected Iterator<T> iterator = new Iterator<>(this::isLast);

    @JsonIgnore
    protected StringBuilder cumulate = new StringBuilder();
    @JsonIgnore
    protected String id = GroceryUtil.generateId();

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
}
