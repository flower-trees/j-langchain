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

package org.salt.jlangchain.rag.vector;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.salt.jlangchain.rag.embedding.Embeddings;
import org.salt.jlangchain.rag.media.Document;
import org.springframework.util.CollectionUtils;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Lightweight in-process vector store for demos and tests.
 *
 * <p>This implementation keeps vectors in memory and performs a linear cosine
 * scan. It is not intended for production-scale retrieval, but it keeps RAG
 * demos reproducible without requiring an external vector database.
 */
public class InMemoryVectorStore extends VectorStore {

    private final List<Entry> entries = new ArrayList<>();

    public InMemoryVectorStore(Embeddings embeddingFunction) {
        this.embeddingFunction = embeddingFunction;
    }

    @Override
    public List<Long> addText(List<String> texts, List<Map<String, Object>> metadatas, List<Long> ids, Long fileId) {
        if (CollectionUtils.isEmpty(texts)) {
            return List.of();
        }

        List<List<Float>> embeddings = embeddingFunction.embedDocuments(texts);
        if (embeddings.size() != texts.size()) {
            throw new IllegalStateException("embedding count does not match text count");
        }

        List<Long> finalIds = CollectionUtils.isEmpty(ids) ? generateIds(texts.size()) : ids;
        if (finalIds.size() != texts.size()) {
            throw new IllegalArgumentException("id count does not match text count");
        }

        for (int i = 0; i < texts.size(); i++) {
            Map<String, Object> metadata = !CollectionUtils.isEmpty(metadatas) && i < metadatas.size()
                    ? metadatas.get(i)
                    : Map.of();
            entries.add(new Entry(finalIds.get(i), fileId == null ? 0L : fileId, texts.get(i), metadata, embeddings.get(i)));
        }
        return finalIds;
    }

    @Override
    public List<Long> addDocument(List<Document> documents, Long fileId) {
        if (CollectionUtils.isEmpty(documents)) {
            return List.of();
        }
        List<String> texts = new ArrayList<>();
        List<Map<String, Object>> metadatas = new ArrayList<>();
        for (Document document : documents) {
            texts.add(document.getPageContent());
            metadatas.add(document.getMetadata() != null ? document.getMetadata() : Map.of());
        }
        return addText(texts, metadatas, List.of(), fileId);
    }

    @Override
    public boolean delete(List<Long> ids) {
        if (CollectionUtils.isEmpty(ids)) {
            return false;
        }
        return entries.removeIf(entry -> ids.contains(entry.getId()));
    }

    @Override
    public List<Document> getByIds(List<Long> ids) {
        if (CollectionUtils.isEmpty(ids)) {
            return List.of();
        }
        return entries.stream()
                .filter(entry -> ids.contains(entry.getId()))
                .map(this::toDocument)
                .toList();
    }

    @Override
    public List<Document> similaritySearch(String query, int k) {
        if (CollectionUtils.isEmpty(entries) || k <= 0) {
            return List.of();
        }
        List<Float> queryVector = embeddingFunction.embedQuery(query);
        if (CollectionUtils.isEmpty(queryVector)) {
            return List.of();
        }
        return entries.stream()
                .map(entry -> new ScoredEntry(entry, cosine(queryVector, entry.getVector())))
                .sorted(Comparator.comparingDouble(ScoredEntry::score).reversed())
                .limit(k)
                .map(scored -> toDocument(scored.entry()))
                .toList();
    }

    @Override
    public BaseRetriever asRetriever() {
        VectorStoreRetriever retriever = new VectorStoreRetriever();
        retriever.setVectorstore(this);
        return retriever;
    }

    public static VectorStore fromText(List<String> texts, Embeddings embedding) {
        return fromText(texts, embedding, List.of(), List.of(), 0L);
    }

    public static VectorStore fromText(List<String> texts, Embeddings embedding, Long fileId) {
        return fromText(texts, embedding, List.of(), List.of(), fileId);
    }

    public static VectorStore fromText(List<String> texts, Embeddings embedding,
                                       List<Map<String, Object>> metadatas, List<Long> ids, Long fileId) {
        InMemoryVectorStore store = new InMemoryVectorStore(embedding);
        store.addText(texts, metadatas, ids, fileId);
        return store;
    }

    public static VectorStore fromDocuments(List<Document> documents, Embeddings embedding) {
        return fromDocuments(documents, embedding, 0L);
    }

    public static VectorStore fromDocuments(List<Document> documents, Embeddings embedding, Long fileId) {
        InMemoryVectorStore store = new InMemoryVectorStore(embedding);
        store.addDocument(documents, fileId);
        return store;
    }

    private List<Long> generateIds(int size) {
        List<Long> ids = new ArrayList<>();
        for (int i = 0; i < size; i++) {
            ids.add(idGenerator.apply(null));
        }
        return ids;
    }

    private Document toDocument(Entry entry) {
        return Document.builder()
                .id(entry.getId())
                .pageContent(entry.getText())
                .fileId(entry.getFileId())
                .metadata(entry.getMetadata())
                .build();
    }

    private double cosine(List<Float> a, List<Float> b) {
        int size = Math.min(a.size(), b.size());
        if (size == 0) {
            return 0D;
        }
        double dot = 0D;
        double normA = 0D;
        double normB = 0D;
        for (int i = 0; i < size; i++) {
            double av = a.get(i);
            double bv = b.get(i);
            dot += av * bv;
            normA += av * av;
            normB += bv * bv;
        }
        if (normA == 0D || normB == 0D) {
            return 0D;
        }
        return dot / (Math.sqrt(normA) * Math.sqrt(normB));
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    private static class Entry {
        private Long id;
        private Long fileId;
        private String text;
        private Map<String, Object> metadata;
        private List<Float> vector;
    }

    private record ScoredEntry(Entry entry, double score) {
    }
}
