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

import lombok.Data;
import lombok.EqualsAndHashCode;

@EqualsAndHashCode(callSuper = true)
@Data
public class PlaceholderMessage extends BaseMessage {

    protected PlaceholderMessage(PlaceholderMessageBuilder<?, ?> builder) {
        super(builder);
    }

    public static PlaceholderMessageBuilder<?, ?> builder() {
        return new PlaceholderMessageBuilderImpl();
    }

    public static abstract class PlaceholderMessageBuilder<C extends PlaceholderMessage, B extends PlaceholderMessageBuilder<C, B>> extends BaseMessageBuilder<C, B> {
        @Override
        public String toString() {
            return "PlaceholderMessage.PlaceholderMessageBuilder(super=" + super.toString() + ")";
        }
    }

    private static final class PlaceholderMessageBuilderImpl extends PlaceholderMessageBuilder<PlaceholderMessage, PlaceholderMessageBuilderImpl> {
        private PlaceholderMessageBuilderImpl() {
        }

        @Override
        protected PlaceholderMessageBuilderImpl self() {
            return this;
        }

        @Override
        public PlaceholderMessage build() {
            return new PlaceholderMessage(this);
        }
    }
}
