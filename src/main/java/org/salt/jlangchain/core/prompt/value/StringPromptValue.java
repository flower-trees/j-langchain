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

import lombok.Data;
import lombok.EqualsAndHashCode;

@EqualsAndHashCode(callSuper = false)
@Data
public class StringPromptValue extends PromptValue {

    private String text;

    public StringPromptValue() {
        super();
    }

    protected StringPromptValue(StringPromptValueBuilder<?, ?> builder) {
        super(builder);
        this.text = builder.text;
    }

    public static StringPromptValueBuilder<?, ?> builder() {
        return new StringPromptValueBuilderImpl();
    }

    public static abstract class StringPromptValueBuilder<C extends StringPromptValue, B extends StringPromptValueBuilder<C, B>> extends PromptValueBuilder<C, B> {
        private String text;

        public B text(String text) {
            this.text = text;
            return self();
        }

        @Override
        public String toString() {
            return "StringPromptValue.StringPromptValueBuilder(super=" + super.toString() + ", text=" + this.text + ")";
        }
    }

    private static final class StringPromptValueBuilderImpl extends StringPromptValueBuilder<StringPromptValue, StringPromptValueBuilderImpl> {
        private StringPromptValueBuilderImpl() {
        }

        @Override
        protected StringPromptValueBuilderImpl self() {
            return this;
        }

        @Override
        public StringPromptValue build() {
            return new StringPromptValue(this);
        }
    }
}
