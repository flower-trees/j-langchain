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
import org.salt.jlangchain.ai.common.param.AiChatOutput;

import java.util.List;

@Data
public class ToolMessage extends AIMessage {

    protected String name;
    protected String toolCallId;
    protected List<AiChatOutput.ToolCall> toolCalls; // Tool calls in response
    protected AiChatOutput.FunctionCall functionCall;

    private String role = MessageType.TOOL.getCode();

    private static String defaultRole() {
        return MessageType.TOOL.getCode();
    }

    protected ToolMessage(ToolMessageBuilder<?, ?> builder) {
        super(builder);
        this.name = builder.name;
        this.toolCallId = builder.toolCallId;
        this.toolCalls = builder.toolCalls;
        this.functionCall = builder.functionCall;
        if (builder.roleSet) {
            this.role = builder.roleValue;
        } else {
            this.role = defaultRole();
        }
    }

    public static ToolMessageBuilder<?, ?> builder() {
        return new ToolMessageBuilderImpl();
    }

    public static abstract class ToolMessageBuilder<C extends ToolMessage, B extends ToolMessageBuilder<C, B>> extends AIMessageBuilder<C, B> {
        private String name;
        private String toolCallId;
        private List<AiChatOutput.ToolCall> toolCalls;
        private AiChatOutput.FunctionCall functionCall;
        private boolean roleSet;
        private String roleValue;

        public B name(String name) {
            this.name = name;
            return self();
        }

        public B toolCallId(String toolCallId) {
            this.toolCallId = toolCallId;
            return self();
        }

        public B toolCalls(List<AiChatOutput.ToolCall> toolCalls) {
            this.toolCalls = toolCalls;
            return self();
        }

        public B functionCall(AiChatOutput.FunctionCall functionCall) {
            this.functionCall = functionCall;
            return self();
        }

        public B role(String role) {
            this.roleValue = role;
            this.roleSet = true;
            return self();
        }

        @Override
        public String toString() {
            return "ToolMessage.ToolMessageBuilder(super=" + super.toString() + ", name=" + this.name + ", toolCallId=" + this.toolCallId + ", toolCalls=" + this.toolCalls + ", functionCall=" + this.functionCall + ", roleValue=" + this.roleValue + ")";
        }
    }

    private static final class ToolMessageBuilderImpl extends ToolMessageBuilder<ToolMessage, ToolMessageBuilderImpl> {
        private ToolMessageBuilderImpl() {
        }

        @Override
        protected ToolMessageBuilderImpl self() {
            return this;
        }

        @Override
        public ToolMessage build() {
            return new ToolMessage(this);
        }
    }
}
