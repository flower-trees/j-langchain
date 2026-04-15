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

package org.salt.jlangchain.rag.media;

import lombok.Data;

import java.util.Map;

@Data
public class BaseMedia {
    protected Long id;
    protected Map<String, Object> metadata;
    protected Boolean isLast = false;

    private static Boolean defaultIsLast() {
        return false;
    }

    public BaseMedia() {
    }

    protected BaseMedia(BaseMediaBuilder<?, ?> builder) {
        this.id = builder.id;
        this.metadata = builder.metadata;
        this.isLast = builder.isLastSet ? builder.isLastValue : defaultIsLast();
    }

    public static BaseMediaBuilder<?, ?> builder() {
        return new BaseMediaBuilderImpl();
    }

    public static abstract class BaseMediaBuilder<C extends BaseMedia, B extends BaseMediaBuilder<C, B>> {
        private Long id;
        private Map<String, Object> metadata;
        private Boolean isLastValue;
        private boolean isLastSet;

        protected abstract B self();

        public abstract C build();

        public B id(Long id) {
            this.id = id;
            return self();
        }

        public B metadata(Map<String, Object> metadata) {
            this.metadata = metadata;
            return self();
        }

        public B isLast(Boolean isLast) {
            this.isLastValue = isLast;
            this.isLastSet = true;
            return self();
        }

        @Override
        public String toString() {
            return "BaseMedia.BaseMediaBuilder(id=" + this.id + ", metadata=" + this.metadata + ", isLastValue=" + this.isLastValue + ")";
        }
    }

    private static final class BaseMediaBuilderImpl extends BaseMediaBuilder<BaseMedia, BaseMediaBuilderImpl> {
        private BaseMediaBuilderImpl() {
        }

        @Override
        protected BaseMediaBuilderImpl self() {
            return this;
        }

        @Override
        public BaseMedia build() {
            return new BaseMedia(this);
        }
    }
}
