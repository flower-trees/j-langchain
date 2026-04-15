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
import org.salt.jlangchain.core.Serializable;

import java.util.Map;

@EqualsAndHashCode(callSuper = true)
@Data
public class BaseMessage extends Serializable {
    protected String id;
    protected String role;
    protected String content;
    protected Map<String, Object> responseMetadata;
    protected Map<String, Object> additionalKwargs;
    protected String finishReason;

    public static BaseMessage fromMessage(String role, String message) {
        return fromMessage(role, message, null, null);
    }

    public static BaseMessage fromMessage(String role, String message, String name, String toolCallId) {
        return switch (MessageType.fromCode(role)) {
            case SYSTEM -> SystemMessage.builder().content(message).build();
            case AI -> AIMessage.builder().content(message).build();
            case HUMAN -> HumanMessage.builder().content(message).build();
            case TOOL -> ToolMessage.builder().content(message).name(name).toolCallId(toolCallId).build();
        };
    }

    public BaseMessage() {
        super();
    }

    public static BaseMessageBuilder<?, ?> builder() {
        return new BaseMessageBuilderImpl();
    }

    protected BaseMessage(BaseMessageBuilder<?, ?> builder) {
        super(builder);
        this.id = builder.id;
        this.role = builder.role;
        this.content = builder.content;
        this.responseMetadata = builder.responseMetadata;
        this.additionalKwargs = builder.additionalKwargs;
        this.finishReason = builder.finishReason;
    }

    private static final class BaseMessageBuilderImpl extends BaseMessageBuilder<BaseMessage, BaseMessageBuilderImpl> {
        private BaseMessageBuilderImpl() {
        }

        @Override
        protected BaseMessageBuilderImpl self() {
            return this;
        }

        @Override
        public BaseMessage build() {
            return new BaseMessage(this);
        }
    }

    public static abstract class BaseMessageBuilder<C extends BaseMessage, B extends BaseMessageBuilder<C, B>> extends SerializableBuilder<C, B> {
        private String id;
        private String role;
        private String content;
        private Map<String, Object> responseMetadata;
        private Map<String, Object> additionalKwargs;
        private String finishReason;

        public B id(String id) {
            this.id = id;
            return self();
        }

        public B role(String role) {
            this.role = role;
            return self();
        }

        public B content(String content) {
            this.content = content;
            return self();
        }

        public B responseMetadata(Map<String, Object> responseMetadata) {
            this.responseMetadata = responseMetadata;
            return self();
        }

        public B additionalKwargs(Map<String, Object> additionalKwargs) {
            this.additionalKwargs = additionalKwargs;
            return self();
        }

        public B finishReason(String finishReason) {
            this.finishReason = finishReason;
            return self();
        }

        @Override
        public String toString() {
            return "BaseMessage.BaseMessageBuilder(super=" + super.toString() + ", id=" + this.id + ", role=" + this.role + ", content=" + this.content + ", responseMetadata=" + this.responseMetadata + ", additionalKwargs=" + this.additionalKwargs + ", finishReason=" + this.finishReason + ")";
        }

        @Override
        protected abstract B self();
    }
}
