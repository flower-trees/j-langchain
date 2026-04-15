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

package org.salt.jlangchain.core.prompt.value;

import lombok.Getter;
import org.salt.jlangchain.core.Serializable;

@Getter
public abstract class PromptValue extends Serializable {

    protected PromptValue() {
    }

    protected PromptValue(PromptValueBuilder<?, ?> builder) {
        super(builder);
    }

    public static abstract class PromptValueBuilder<C extends PromptValue, B extends PromptValueBuilder<C, B>> extends SerializableBuilder<C, B> {
        @Override
        public String toString() {
            return "PromptValue.PromptValueBuilder(super=" + super.toString() + ")";
        }
    }
}
