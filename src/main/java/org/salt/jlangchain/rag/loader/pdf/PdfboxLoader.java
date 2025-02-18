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
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.text.PDFTextStripper;
import org.salt.function.flow.thread.TheadHelper;
import org.salt.jlangchain.core.common.Iterator;
import org.salt.jlangchain.rag.media.Document;
import org.salt.jlangchain.rag.loader.ocr.OcrInstance;
import org.salt.jlangchain.utils.SpringContextUtil;

import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

@Slf4j
@Getter
@Setter
public class PdfboxLoader extends BasePDFLoader {

    protected Iterator<Document> iterator = new Iterator<>(this::isLast);

    @Override
    public List<Document> load() {

        if (StringUtils.isNotEmpty(filePath)) {
            try (PDDocument document = PDDocument.load(new File(filePath))) {
                List<Document> documents = new ArrayList<>();
                readTextFromFile(document, documents::add);
                return documents;
            }  catch (Exception e) {
                log.error("load error reading PDF file:", e);
            }
        }

        return List.of();
    }

    @Override
    public Iterator<Document> lazyLoad() {

        SpringContextUtil.getApplicationContext().getBean(TheadHelper.class).submit(() -> {
            if (StringUtils.isNotEmpty(filePath)) {
                try (PDDocument document = PDDocument.load(new File(filePath))) {
                    readTextFromFile(document, doc -> {
                        try {
                            iterator.append(doc);
                        } catch (TimeoutException e) {
                            throw new RuntimeException(e);
                        }
                    });
                }  catch (Exception e) {
                    log.error("lazyLoad error reading PDF file:", e);
                }
            }
        });
        return iterator;
    }

    private Boolean isLast(Document document) {
        return document.isLast();
    }

    private void readTextFromFile(PDDocument document, Consumer<Document> callback) throws Exception {

        int numberOfPages = document.getNumberOfPages();
        for (int i = 0; i < numberOfPages; i++) {
            int pageNumber = i + 1;
            Document pageDoc = new Document();

            PDFTextStripper textStripper = new PDFTextStripper();
            textStripper.setStartPage(pageNumber);
            textStripper.setEndPage(pageNumber);
            StringBuilder pageText = new StringBuilder(textStripper.getText(document));

            if (extractImages) {
                OcrInstance ocrInstance = SpringContextUtil.getBean(ocrClazz);
                PDPage page = document.getPage(i);
                PDResources resources = page.getResources();
                for (COSName xObjectName : resources.getXObjectNames()) {
                    if (resources.isImageXObject(xObjectName)) {
                        try {
                            PDImageXObject imageObject = (PDImageXObject) resources.getXObject(xObjectName);
                            BufferedImage bImage = imageObject.getImage();
                            if (bImage.getHeight() > 100 && bImage.getWidth() > 100) {
                                pageText.append(ocrInstance.doOCR(bImage));
                            }
                        } catch (Exception e) {
                            log.warn("Error extracting images from PDF file", e);
                        }
                    }
                }
            }

            pageDoc.setPageContent(pageText.toString());
            pageDoc.setLast(i == numberOfPages - 1);
            callback.accept(pageDoc);
        }
    }
}
