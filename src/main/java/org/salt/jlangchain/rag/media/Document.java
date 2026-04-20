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
import lombok.EqualsAndHashCode;

@EqualsAndHashCode(callSuper = true)
@Data
public class Document extends BaseMedia {
    protected String pageContent;
    public Long fileId;

    public Document() {
        super();
    }

    protected Document(DocumentBuilder<?, ?> builder) {
        super(builder);
        this.pageContent = builder.pageContent;
        this.fileId = builder.fileId;
    }

    public static DocumentBuilder<?, ?> builder() {
        return new DocumentBuilderImpl();
    }

    public static abstract class DocumentBuilder<C extends Document, B extends DocumentBuilder<C, B>> extends BaseMediaBuilder<C, B> {
        private String pageContent;
        private Long fileId;

        public B pageContent(String pageContent) {
            this.pageContent = pageContent;
            return self();
        }

        public B fileId(Long fileId) {
            this.fileId = fileId;
            return self();
        }

        @Override
        public String toString() {
            return "Document.DocumentBuilder(super=" + super.toString() + ", pageContent=" + this.pageContent + ", fileId=" + this.fileId + ")";
        }
    }

    private static final class DocumentBuilderImpl extends DocumentBuilder<Document, DocumentBuilderImpl> {
        private DocumentBuilderImpl() {
        }

        @Override
        protected DocumentBuilderImpl self() {
            return this;
        }

        @Override
        public Document build() {
            return new Document(this);
        }
    }
}
