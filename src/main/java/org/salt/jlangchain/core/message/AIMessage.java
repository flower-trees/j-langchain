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

@Data
public class AIMessage extends BaseMessage {

    private String role = MessageType.AI.getCode();

    private static String defaultRole() {
        return MessageType.AI.getCode();
    }

    protected AIMessage(AIMessageBuilder<?, ?> builder) {
        super(builder);
        if (builder.roleSet) {
            this.role = builder.roleValue;
        } else {
            this.role = defaultRole();
        }
    }

    public static AIMessageBuilder<?, ?> builder() {
        return new AIMessageBuilderImpl();
    }

    public static abstract class AIMessageBuilder<C extends AIMessage, B extends AIMessageBuilder<C, B>> extends BaseMessageBuilder<C, B> {
        private boolean roleSet;
        private String roleValue;

        public B role(final String role) {
            this.roleValue = role;
            this.roleSet = true;
            return self();
        }

        @Override
        public String toString() {
            return "AIMessage.AIMessageBuilder(super=" + super.toString() + ", roleValue=" + this.roleValue + ")";
        }
    }

    private static final class AIMessageBuilderImpl extends AIMessageBuilder<AIMessage, AIMessageBuilderImpl> {
        private AIMessageBuilderImpl() {
        }

        @Override
        protected AIMessageBuilderImpl self() {
            return this;
        }

        @Override
        public AIMessage build() {
            return new AIMessage(this);
        }
    }
}
