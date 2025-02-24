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

package org.salt.jlangchain.rag.loader.ocr;

import lombok.extern.slf4j.Slf4j;
import net.sourceforge.tess4j.Tesseract;
import org.springframework.beans.factory.annotation.Value;

import javax.annotation.PostConstruct;
import java.awt.image.BufferedImage;

@Slf4j
public class TesseractActuator implements OcrActuator {

    @Value("${rag.ocr.tesseract.path:${TESSDATA_PATH:}}")
    private String tessdataPath;

    private Tesseract tesseract;

    @PostConstruct
    private void init() {
        System.setProperty("jna.library.path", tessdataPath + "/lib");
        System.setProperty("TESSDATA_PREFIX", tessdataPath + "/share");

        tesseract = new Tesseract();
        tesseract.setDatapath(tessdataPath + "/share/tessdata");
        tesseract.setLanguage("eng+chi_sim");
    }

    public String doOCR(BufferedImage bImage) throws Exception {
        return tesseract.doOCR(bImage);
    }
}