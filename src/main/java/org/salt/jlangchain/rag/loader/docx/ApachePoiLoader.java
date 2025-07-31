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

import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.SuperBuilder;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.apache.poi.xwpf.usermodel.*;
import org.salt.function.flow.thread.TheadHelper;
import org.salt.jlangchain.core.common.Iterator;
import org.salt.jlangchain.rag.loader.ocr.OcrActuator;
import org.salt.jlangchain.rag.media.Document;
import org.salt.jlangchain.utils.SpringContextUtil;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.FileInputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

@EqualsAndHashCode(callSuper = true)
@Data
@SuperBuilder
@Slf4j
public class ApachePoiLoader extends BaseDocxLoader {

    @Builder.Default
    protected Iterator<Document> iterator = new Iterator<>(ApachePoiLoader::isLast);

    @Override
    public List<Document> load() {
        if (StringUtils.isNotEmpty(filePath)) {
            try (FileInputStream inputStream = new FileInputStream(filePath)) {
                try (XWPFDocument document = new XWPFDocument(inputStream)) {
                    List<Document> documents = new ArrayList<>();
                    readTextFromFile(document, documents::add);
                    return documents;
                } catch (Exception e) {
                    log.error("load error reading docx file:", e);
                }
            } catch (Exception e) {
                log.error("load error reading docx file:", e);
            }
        } else if (inputStream != null) {
            try (XWPFDocument document = new XWPFDocument(inputStream)) {
                List<Document> documents = new ArrayList<>();
                readTextFromFile(document, documents::add);
                return documents;
            }  catch (Exception e) {
                log.error("load error reading docx file:", e);
            }
        }
        return List.of();
    }

    @Override
    public Iterator<Document> lazyLoad() {
        SpringContextUtil.getApplicationContext().getBean(TheadHelper.class).submit(() -> {
            if (StringUtils.isNotEmpty(filePath)) {
                try (FileInputStream inputStream = new FileInputStream(filePath)) {
                    try (XWPFDocument document = new XWPFDocument(inputStream)) {
                        readTextFromFileLazy(document, doc -> {
                            try {
                                iterator.append(doc);
                            } catch (TimeoutException e) {
                                throw new RuntimeException(e);
                            }
                        });
                    } catch (Exception e) {
                        log.error("lazyLoad load error reading docx file:", e);
                    }
                }  catch (Exception e) {
                    log.error("lazyLoad error reading PDF file:", e);
                }
            } else if (inputStream != null) {
                try (XWPFDocument document = new XWPFDocument(inputStream)) {
                    readTextFromFileLazy(document, doc -> {
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

    private static Boolean isLast(Document document) {
        return document.getIsLast();
    }

    private void readTextFromFile(XWPFDocument document, Consumer<Document> callback) {
        StringBuilder pageText = new StringBuilder();
        List<XWPFParagraph> paragraphs = document.getParagraphs();
        for (int i = 0; i < paragraphs.size(); i++) {
            pageText.append(paragraphs.get(i).getText());
            if (extractImages) {
                extracted(paragraphs, i, pageText);
            }
        }
        Document pageDoc = Document.builder().build();
        pageDoc.setPageContent(pageText.toString());
        pageDoc.setIsLast(true);
        callback.accept(pageDoc);
    }

    private void readTextFromFileLazy(XWPFDocument document, Consumer<Document> callback) {
        List<XWPFParagraph> paragraphs = document.getParagraphs();
        for (int i = 0; i < paragraphs.size(); i++) {
            StringBuilder pageText = new StringBuilder();
            pageText.append(paragraphs.get(i).getText());
            if (extractImages) {
                extracted(paragraphs, i, pageText);
            }
            Document pageDoc = Document.builder().build();
            pageDoc.setPageContent(pageText.toString());
            pageDoc.setIsLast(i == paragraphs.size() - 1);
            callback.accept(pageDoc);
        }
    }

    private void extracted(List<XWPFParagraph> paragraphs, int i, StringBuilder pageText) {
        OcrActuator ocrActuator = SpringContextUtil.getBean(ocrClazz);
        for (XWPFRun run : paragraphs.get(i).getRuns()) {
            List<XWPFPicture> pictures = run.getEmbeddedPictures();
            for (XWPFPicture picture : pictures) {
                XWPFPictureData pictureData = picture.getPictureData();
                byte[] imageData = pictureData.getData();
                try {
                    ByteArrayInputStream bis = new ByteArrayInputStream(imageData);
                    BufferedImage bImage = ImageIO.read(bis);
                    if (bImage.getHeight() > 100 && bImage.getWidth() > 100) {
                        pageText.append(ocrActuator.doOCR(bImage));
                    }
                } catch (Exception e) {
                    log.warn("Error extracting images from docx file", e);
                }
            }
        }
    }
}
