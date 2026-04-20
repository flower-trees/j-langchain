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

package org.salt.jlangchain.rag.embedding;

import lombok.Data;
import lombok.EqualsAndHashCode;
import org.salt.jlangchain.ai.chat.strategy.AiChatActuator;
import org.salt.jlangchain.ai.vendor.chatgpt.ChatGPTActuator;

@EqualsAndHashCode(callSuper = true)
@Data
public class OpenAIEmbeddings extends Embeddings {

    public OpenAIEmbeddings() {
        super();
        this.model = "text-embedding-ada-002";
        this.vectorSize = 1536;
    }

    protected OpenAIEmbeddings(OpenAIEmbeddingsBuilder<?, ?> builder) {
        super();
        this.model = "text-embedding-ada-002";
        this.vectorSize = 1536;
        if (builder.modelSet) {
            this.model = builder.modelValue;
        }
        if (builder.vectorSizeSet) {
            this.vectorSize = builder.vectorSizeValue;
        }
    }

    public static OpenAIEmbeddingsBuilder<?, ?> builder() {
        return new OpenAIEmbeddingsBuilderImpl();
    }

    public static abstract class OpenAIEmbeddingsBuilder<C extends OpenAIEmbeddings, B extends OpenAIEmbeddingsBuilder<C, B>> extends EmbeddingsBuilder<C, B> {
        private boolean modelSet;
        private String modelValue;
        private boolean vectorSizeSet;
        private Integer vectorSizeValue;

        public B model(String model) {
            this.modelValue = model;
            this.modelSet = true;
            return self();
        }

        public B vectorSize(int vectorSize) {
            this.vectorSizeValue = vectorSize;
            this.vectorSizeSet = true;
            return self();
        }

        @Override
        public String toString() {
            return "OpenAIEmbeddings.OpenAIEmbeddingsBuilder(super=" + super.toString() + ", modelValue=" + this.modelValue + ", vectorSizeValue=" + this.vectorSizeValue + ")";
        }
    }

    private static final class OpenAIEmbeddingsBuilderImpl extends OpenAIEmbeddingsBuilder<OpenAIEmbeddings, OpenAIEmbeddingsBuilderImpl> {
        private OpenAIEmbeddingsBuilderImpl() {
        }

        @Override
        protected OpenAIEmbeddingsBuilderImpl self() {
            return this;
        }

        @Override
        public OpenAIEmbeddings build() {
            return new OpenAIEmbeddings(this);
        }
    }

    @Override
    public Class<? extends AiChatActuator> getActuator() {
        return ChatGPTActuator.class;
    }
}
