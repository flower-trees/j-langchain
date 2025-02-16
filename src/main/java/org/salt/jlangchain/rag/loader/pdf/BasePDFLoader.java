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

package org.salt.jlangchain.rag.loader.pdf;

import lombok.Getter;
import lombok.Setter;
import org.salt.jlangchain.rag.loader.BaseLoader;
import org.salt.jlangchain.rag.loader.ocr.OcrInstance;
import org.salt.jlangchain.rag.loader.ocr.TesseractInstance;

@Getter
@Setter
public abstract class BasePDFLoader extends BaseLoader {

    protected String filePath;
    protected String webPath;
    protected boolean extractImages = false;
    protected Class<? extends OcrInstance> ocrClazz = TesseractInstance.class;
}
