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
import org.salt.jlangchain.core.message.BaseMessage;

import java.util.List;

@Data
public class ChatPromptValue extends PromptValue {

    private List<BaseMessage> messages;

    public ChatPromptValue() {
        super();
    }

    protected ChatPromptValue(ChatPromptValueBuilder<?, ?> builder) {
        super(builder);
        this.messages = builder.messages;
    }

    public static ChatPromptValueBuilder<?, ?> builder() {
        return new ChatPromptValueBuilderImpl();
    }

    public static abstract class ChatPromptValueBuilder<C extends ChatPromptValue, B extends ChatPromptValueBuilder<C, B>> extends PromptValueBuilder<C, B> {
        private List<BaseMessage> messages;

        public B messages(List<BaseMessage> messages) {
            this.messages = messages;
            return self();
        }

        @Override
        public String toString() {
            return "ChatPromptValue.ChatPromptValueBuilder(super=" + super.toString() + ", messages=" + this.messages + ")";
        }
    }

    private static final class ChatPromptValueBuilderImpl extends ChatPromptValueBuilder<ChatPromptValue, ChatPromptValueBuilderImpl> {
        private ChatPromptValueBuilderImpl() {
        }

        @Override
        protected ChatPromptValueBuilderImpl self() {
            return this;
        }

        @Override
        public ChatPromptValue build() {
            return new ChatPromptValue(this);
        }
    }
}
