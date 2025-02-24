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

package org.salt.jlangchain.rag.splitter;

import lombok.*;
import lombok.experimental.SuperBuilder;
import lombok.extern.slf4j.Slf4j;
import org.salt.jlangchain.rag.media.Document;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Data
@SuperBuilder
@Slf4j
public abstract class TextSplitter {

    @Builder.Default
    protected int chunkSize = 4000;
    @Builder.Default
    protected int chunkOverlap = 200;

    public abstract List<String> splitText(String text);

    public List<Document> splitDocument(List<Document> documents) {
        List<Document> result = new ArrayList<>();
        String textMerge = documents.stream()
                .map(Document::getPageContent)
                .collect(Collectors.joining(""));
        List<String> texts = splitText(textMerge);
        for (String text : texts) {
            Document newDoc = Document.builder().pageContent(text).metadata(Map.of()).build();
            result.add(newDoc);
        }
        return result;
    }

    public List<Document> splitDocumentInPage(List<Document> documents) {
        List<Document> result = new ArrayList<>();
        for (Document doc : documents) {
            List<String> texts = splitText(doc.getPageContent());
            for (String text : texts) {
                Document newDoc = Document.builder().pageContent(text).metadata(doc.getMetadata()).build();
                result.add(newDoc);
            }
        }
        return result;
    }

    private int length(String str) {
        return str.length();
    }

    private String joinDocs(List<String> currentDoc, String separator) {
        if (currentDoc.isEmpty()) {
            return null;
        }
        return String.join(separator, currentDoc);
    }

    public List<String> mergeSplits(List<String> splits, String separator) {
        int separatorLen = length(separator);
        List<String> docs = new ArrayList<>();
        List<String> currentDoc = new ArrayList<>();
        int total = 0;

        for (String d : splits) {
            int len = length(d);

            if (total + len + (!currentDoc.isEmpty() ? separatorLen : 0) > chunkSize) {
                if (total > chunkSize) {
                    log.warn("Warning: Created a chunk of size " + total + " which is longer than the specified " + chunkSize);
                }

                if (!currentDoc.isEmpty()) {
                    String doc = joinDocs(currentDoc, separator);
                    if (doc != null) {
                        docs.add(doc);
                    }
                    // Pop elements if chunk exceeds size or overlap condition
                    while (total > chunkOverlap || (total + len + (!currentDoc.isEmpty() ? separatorLen : 0) > chunkSize && total > 0)) {
                        total -= length(currentDoc.get(0)) + (currentDoc.size() > 1 ? separatorLen : 0);
                        currentDoc.remove(0);
                    }
                }
            }
            currentDoc.add(d);
            total += len + (currentDoc.size() > 1 ? separatorLen : 0);
        }

        String finalDoc = joinDocs(currentDoc, separator);
        if (finalDoc != null) {
            docs.add(finalDoc);
        }

        return docs;
    }
}