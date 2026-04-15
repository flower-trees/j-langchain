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

package org.salt.jlangchain.rag.loader.docx;

import lombok.Data;
import lombok.EqualsAndHashCode;
import org.salt.jlangchain.rag.loader.BaseLoader;
import org.salt.jlangchain.rag.loader.ocr.OcrActuator;
import org.salt.jlangchain.rag.loader.ocr.TesseractActuator;

import java.io.InputStream;

@EqualsAndHashCode(callSuper = false)
@Data
public abstract class BaseDocxLoader extends BaseLoader {

    protected String filePath;
    protected String webPath;
    protected InputStream inputStream;
    protected boolean extractImages = false;
    protected Class<? extends OcrActuator> ocrClazz = TesseractActuator.class;

    protected BaseDocxLoader(BaseDocxLoaderBuilder<?, ?> builder) {
        super(builder);
        this.filePath = builder.filePath;
        this.webPath = builder.webPath;
        this.inputStream = builder.inputStream;
        this.extractImages = builder.extractImagesSet ? builder.extractImagesValue : false;
        this.ocrClazz = builder.ocrClazz != null ? builder.ocrClazz : TesseractActuator.class;
    }

    protected BaseDocxLoader() {
        super();
    }

    public static abstract class BaseDocxLoaderBuilder<C extends BaseDocxLoader, B extends BaseDocxLoaderBuilder<C, B>> extends BaseLoaderBuilder<C, B> {
        private String filePath;
        private String webPath;
        private InputStream inputStream;
        private boolean extractImagesValue;
        private boolean extractImagesSet;
        private Class<? extends OcrActuator> ocrClazz;

        public B filePath(String filePath) {
            this.filePath = filePath;
            return self();
        }

        public B webPath(String webPath) {
            this.webPath = webPath;
            return self();
        }

        public B inputStream(InputStream inputStream) {
            this.inputStream = inputStream;
            return self();
        }

        public B extractImages(boolean extractImages) {
            this.extractImagesValue = extractImages;
            this.extractImagesSet = true;
            return self();
        }

        public B ocrClazz(Class<? extends OcrActuator> ocrClazz) {
            this.ocrClazz = ocrClazz;
            return self();
        }

        @Override
        public String toString() {
            return "BaseDocxLoader.BaseDocxLoaderBuilder(super=" + super.toString() + ", filePath=" + this.filePath + ", webPath=" + this.webPath + ", inputStream=" + this.inputStream + ", extractImagesValue=" + this.extractImagesValue + ", ocrClazz=" + this.ocrClazz + ")";
        }
    }
}
