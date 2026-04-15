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
public class SystemMessage extends BaseMessage {

    private String role = MessageType.SYSTEM.getCode();

    private static String defaultRole() {
        return MessageType.SYSTEM.getCode();
    }

    protected SystemMessage(SystemMessageBuilder<?, ?> builder) {
        super(builder);
        if (builder.roleSet) {
            this.role = builder.roleValue;
        } else {
            this.role = defaultRole();
        }
    }

    public static SystemMessageBuilder<?, ?> builder() {
        return new SystemMessageBuilderImpl();
    }

    public static abstract class SystemMessageBuilder<C extends SystemMessage, B extends SystemMessageBuilder<C, B>> extends BaseMessageBuilder<C, B> {
        private boolean roleSet;
        private String roleValue;

        public B role(final String role) {
            this.roleValue = role;
            this.roleSet = true;
            return self();
        }

        @Override
        public String toString() {
            return "SystemMessage.SystemMessageBuilder(super=" + super.toString() + ", roleValue=" + this.roleValue + ")";
        }
    }

    private static final class SystemMessageBuilderImpl extends SystemMessageBuilder<SystemMessage, SystemMessageBuilderImpl> {
        private SystemMessageBuilderImpl() {
        }

        @Override
        protected SystemMessageBuilderImpl self() {
            return this;
        }

        @Override
        public SystemMessage build() {
            return new SystemMessage(this);
        }
    }
}
