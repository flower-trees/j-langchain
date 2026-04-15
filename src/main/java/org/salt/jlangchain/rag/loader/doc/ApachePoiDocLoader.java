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

package org.salt.jlangchain.rag.loader.doc;

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.apache.poi.hwpf.HWPFDocument;
import org.apache.poi.hwpf.extractor.WordExtractor;
import org.apache.poi.hwpf.usermodel.Picture;
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

import lombok.Data;

@Data
@Slf4j
public class ApachePoiDocLoader extends BaseDocLoader {

    protected Iterator<Document> iterator = new Iterator<>(ApachePoiDocLoader::isLast);

    public ApachePoiDocLoader() {
        super();
    }

    protected ApachePoiDocLoader(ApachePoiDocLoaderBuilder<?, ?> builder) {
        super(builder);
        this.iterator = builder.iterator != null ? builder.iterator : new Iterator<>(ApachePoiDocLoader::isLast);
    }

    public static ApachePoiDocLoaderBuilder<?, ?> builder() {
        return new ApachePoiDocLoaderBuilderImpl();
    }

    public static abstract class ApachePoiDocLoaderBuilder<C extends ApachePoiDocLoader, B extends ApachePoiDocLoaderBuilder<C, B>> extends BaseDocLoaderBuilder<C, B> {
        private Iterator<Document> iterator;

        public B iterator(Iterator<Document> iterator) {
            this.iterator = iterator;
            return self();
        }

        @Override
        public String toString() {
            return "ApachePoiDocLoader.ApachePoiDocLoaderBuilder(super=" + super.toString() + ", iterator=" + this.iterator + ")";
        }
    }

    private static final class ApachePoiDocLoaderBuilderImpl extends ApachePoiDocLoaderBuilder<ApachePoiDocLoader, ApachePoiDocLoaderBuilderImpl> {
        private ApachePoiDocLoaderBuilderImpl() {
        }

        @Override
        protected ApachePoiDocLoaderBuilderImpl self() {
            return this;
        }

        @Override
        public ApachePoiDocLoader build() {
            return new ApachePoiDocLoader(this);
        }
    }

    @Override
    public List<Document> load() {
        if (StringUtils.isNotEmpty(filePath)) {
            try (FileInputStream inputStream = new FileInputStream(filePath);
                 HWPFDocument document = new HWPFDocument(inputStream)) {
                List<Document> documents = new ArrayList<>();
                readTextFromFile(document, documents::add);
                return documents;
            } catch (Exception e) {
                log.error("load error reading doc file:", e);
            }
        } else if (inputStream != null) {
            try (HWPFDocument document = new HWPFDocument(inputStream)) {
                List<Document> documents = new ArrayList<>();
                readTextFromFile(document, documents::add);
                return documents;
            }  catch (Exception e) {
                log.error("load error reading doc file:", e);
            }
        }
        return List.of();
    }

    @Override
    public Iterator<Document> lazyLoad() {
        SpringContextUtil.getApplicationContext().getBean(TheadHelper.class).submit(() -> {
            if (StringUtils.isNotEmpty(filePath)) {
                try (FileInputStream inputStream = new FileInputStream(filePath);
                     HWPFDocument document = new HWPFDocument(inputStream)) {
                    readTextFromFile(document, doc -> {
                        try {
                            iterator.append(doc);
                        } catch (TimeoutException e) {
                            throw new RuntimeException(e);
                        }
                    });
                } catch (Exception e) {
                    log.error("lazyLoad load error reading doc file:", e);
                }
            } else if (inputStream != null) {
                try (HWPFDocument document = new HWPFDocument(inputStream)) {
                    readTextFromFile(document, doc -> {
                        try {
                            iterator.append(doc);
                        } catch (TimeoutException e) {
                            throw new RuntimeException(e);
                        }
                    });
                }  catch (Exception e) {
                    log.error("lazyLoad error reading doc file:", e);
                }
            }
        });
        return iterator;
    }

    private static Boolean isLast(Document document) {
        return document.getIsLast();
    }

    private void readTextFromFile(HWPFDocument document, Consumer<Document> callback) {
        StringBuilder pageText = new StringBuilder();
        try (WordExtractor extractor = new WordExtractor(document)) {
            pageText.append(extractor.getText());
            if (extractImages) {
                OcrActuator ocrActuator = SpringContextUtil.getBean(ocrClazz);
                List<Picture> pictures = document.getPicturesTable().getAllPictures();
                for (Picture picture : pictures) {
                    byte[] imageData = picture.getContent();
                    try {
                        ByteArrayInputStream bis = new ByteArrayInputStream(imageData);
                        BufferedImage bImage = ImageIO.read(bis);
                        if (bImage.getHeight() > 100 && bImage.getWidth() > 100) {
                            pageText.append(ocrActuator.doOCR(bImage));
                        }
                    } catch (Exception e) {
                        log.warn("Error extracting images from doc file", e);
                    }
                }
            }
        } catch (Exception e) {
            log.error("load error reading doc file:", e);
        }
        Document pageDoc = Document.builder().build();
        pageDoc.setPageContent(pageText.toString());
        pageDoc.setIsLast(true);
        callback.accept(pageDoc);
    }
}
