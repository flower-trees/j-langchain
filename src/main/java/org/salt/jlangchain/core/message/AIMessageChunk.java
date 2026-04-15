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
public class AIMessageChunk extends BaseMessageChunk<AIMessageChunk> {

    private String role = MessageType.AI.getCode();

    private static String defaultRole() {
        return MessageType.AI.getCode();
    }

    protected AIMessageChunk(AIMessageChunkBuilder<?, ?> builder) {
        super(builder);
        if (builder.roleSet) {
            this.role = builder.roleValue;
        } else {
            this.role = defaultRole();
        }
    }

    public AIMessageChunk() {
        super();
    }

    public static AIMessageChunkBuilder<?, ?> builder() {
        return new AIMessageChunkBuilderImpl();
    }

    public static abstract class AIMessageChunkBuilder<C extends AIMessageChunk, B extends AIMessageChunkBuilder<C, B>> extends BaseMessageChunkBuilder<AIMessageChunk, C, B> {
        private boolean roleSet;
        private String roleValue;

        public B role(String role) {
            this.roleValue = role;
            this.roleSet = true;
            return self();
        }

        @Override
        public String toString() {
            return "AIMessageChunk.AIMessageChunkBuilder(super=" + super.toString() + ", roleValue=" + this.roleValue + ")";
        }
    }

    private static final class AIMessageChunkBuilderImpl extends AIMessageChunkBuilder<AIMessageChunk, AIMessageChunkBuilderImpl> {
        private AIMessageChunkBuilderImpl() {
        }

        @Override
        protected AIMessageChunkBuilderImpl self() {
            return this;
        }

        @Override
        public AIMessageChunk build() {
            return new AIMessageChunk(this);
        }
    }
}
