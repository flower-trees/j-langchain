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
import org.salt.jlangchain.ai.vendor.aliyun.AliyunActuator;

@EqualsAndHashCode(callSuper = true)
@Data
public class AliyunEmbeddings extends Embeddings {

    public AliyunEmbeddings() {
        super();
        this.model = "text-embedding-v3";
        this.vectorSize = 768;
    }

    protected AliyunEmbeddings(AliyunEmbeddingsBuilder<?, ?> builder) {
        super();
        this.model = "text-embedding-v3";
        this.vectorSize = 768;
        if (builder.modelSet) {
            this.model = builder.modelValue;
        }
        if (builder.vectorSizeSet) {
            this.vectorSize = builder.vectorSizeValue;
        }
    }

    public static AliyunEmbeddingsBuilder<?, ?> builder() {
        return new AliyunEmbeddingsBuilderImpl();
    }

    public static abstract class AliyunEmbeddingsBuilder<C extends AliyunEmbeddings, B extends AliyunEmbeddingsBuilder<C, B>> extends EmbeddingsBuilder<C, B> {
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
            return "AliyunEmbeddings.AliyunEmbeddingsBuilder(super=" + super.toString() + ", modelValue=" + this.modelValue + ", vectorSizeValue=" + this.vectorSizeValue + ")";
        }
    }

    private static final class AliyunEmbeddingsBuilderImpl extends AliyunEmbeddingsBuilder<AliyunEmbeddings, AliyunEmbeddingsBuilderImpl> {
        private AliyunEmbeddingsBuilderImpl() {
        }

        @Override
        protected AliyunEmbeddingsBuilderImpl self() {
            return this;
        }

        @Override
        public AliyunEmbeddings build() {
            return new AliyunEmbeddings(this);
        }
    }

    @Override
    public Class<? extends AiChatActuator> getActuator() {
        return AliyunActuator.class;
    }
}
