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

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import io.milvus.v2.client.MilvusClientV2;
import io.milvus.v2.common.DataType;
import io.milvus.v2.common.IndexParam;
import io.milvus.v2.service.collection.request.AddFieldReq;
import io.milvus.v2.service.collection.request.CreateCollectionReq;
import io.milvus.v2.service.collection.request.HasCollectionReq;
import io.milvus.v2.service.vector.request.DeleteReq;
import io.milvus.v2.service.vector.request.GetReq;
import io.milvus.v2.service.vector.request.InsertReq;
import io.milvus.v2.service.vector.request.SearchReq;
import io.milvus.v2.service.vector.request.data.FloatVec;
import io.milvus.v2.service.vector.response.DeleteResp;
import io.milvus.v2.service.vector.response.GetResp;
import io.milvus.v2.service.vector.response.InsertResp;
import io.milvus.v2.service.vector.response.SearchResp;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.salt.jlangchain.rag.embedding.Embeddings;
import org.salt.jlangchain.rag.media.Document;
import org.salt.jlangchain.utils.JsonUtil;
import org.salt.jlangchain.utils.SpringContextUtil;
import org.springframework.util.CollectionUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@Getter
@Setter
public class Milvus extends VectorStore {

    protected MilvusClientV2 client;

    protected Gson gson = new Gson();

    protected String collectionName;

    public Milvus(String collectionName, Embeddings embeddingFunction) {
        super();
        this.collectionName = collectionName;
        this.embeddingFunction = embeddingFunction;
        MilvusContainer milvusContainer = SpringContextUtil.getBean(MilvusContainer.class);
        client = milvusContainer.getClient();

        if (!client.hasCollection(HasCollectionReq.builder().collectionName(collectionName).build())) {
            client.createCollection(getCreateCollectionReq());
        }
    }

    @Override
    public List<Long> addText(List<String> tests, List<Map<String, Object>> metadatas, List<Long> ids) {

        if (CollectionUtils.isEmpty(tests)) {
            return List.of();
        }

        List<List<Float>> embeddings = embeddingFunction.embedDocuments(tests);

        if (CollectionUtils.isEmpty(ids)) {
            List<Long> finalIds = new ArrayList<>();
            tests.forEach(test -> {
                finalIds.add(idGenerator.apply(null));
            });
            ids = finalIds;
        }

        InsertResp insertResp = client.insert(getInsetReq(embeddings, tests, ids, metadatas));
        if (insertResp.getInsertCnt() != tests.size()) {
            throw new RuntimeException("insert failed");
        }

        return ids;
    }

    @Override
    public List<Long> addDocument(List<Document> documents) {
        List<String> tests = new ArrayList<>();
        List<Map<String, Object>> metadatas = new ArrayList<>();
        for (Document document : documents) {
            tests.add(document.getPageContent());
            if (!CollectionUtils.isEmpty(document.getMetadata())) {
                metadatas.add(document.getMetadata());
            } else {
                metadatas.add(Map.of());
            }
        }
        return addText(tests, metadatas, List.of());
    }

    @Override
    public boolean delete(List<Long> ids) {
        DeleteResp deleteResp = client.delete(DeleteReq.builder().collectionName(collectionName).ids(ids.stream().map(id -> (Object) id).toList()).build());
        return deleteResp.getDeleteCnt() > 0;
    }

    @Override
    public List<Document> getByIds(List<Long> ids) {
        GetResp getResp = client.get(GetReq.builder().collectionName(collectionName).ids(ids.stream().map(id -> (Object) id).toList()).outputFields(getOutputFields()).build());
        return getDocument(getResp);
    }

    @Override
    public List<Document> similaritySearch(String query, int k) {
        List<Float> queryVector = embeddingFunction.embedQuery(query);
        FloatVec queryVectorFloatVec = new FloatVec(queryVector);

        SearchReq searchReq = SearchReq.builder()
                .collectionName(collectionName)
                .topK(k)
                .metricType(IndexParam.MetricType.COSINE)
                .data(Collections.singletonList(queryVectorFloatVec))
                .outputFields(getOutputFields())
                .build();

        SearchResp searchResp = client.search(searchReq);
        log.info("searchResp:{}", JsonUtil.toJson(searchResp));
        return getDocument(searchResp);
    }

    @Override
    public BaseRetriever asRetriever() {
        VectorStoreRetriever vectorStoreRetriever = new VectorStoreRetriever();
        vectorStoreRetriever.setVectorstore(this);
        return vectorStoreRetriever;
    }

    public static VectorStore fromText(List<String> tests, Embeddings embedding, String collectionName) {
        return fromText(tests, embedding, List.of(), List.of(), collectionName);
    }

    public static VectorStore fromText(List<String> tests, Embeddings embedding, List<Map<String, Object>> metadatas, List<Long> ids, String collectionName) {
        Milvus milvus = new Milvus(collectionName, embedding);
        milvus.setEmbeddingFunction(embedding);
        milvus.addText(tests, metadatas, ids);
        return milvus;
    }

    public static VectorStore fromDocuments(List<Document> documents, Embeddings embedding, String collectionName) {
        List<String> tests = new ArrayList<>();
        List<Map<String, Object>> metadatas = new ArrayList<>();
        documents.forEach(document -> {
            tests.add(document.getPageContent());
            if (!CollectionUtils.isEmpty(document.getMetadata())) {
                metadatas.add(document.getMetadata());
            } else {
                metadatas.add(Map.of());
            }
        });
        return fromText(tests, embedding, metadatas, List.of(), collectionName);
    }

    protected CreateCollectionReq getCreateCollectionReq() {

        CreateCollectionReq.CollectionSchema schema = client.createSchema();

        schema.addField(AddFieldReq.builder()
                .fieldName("id")
                .dataType(DataType.Int64)
                .isPrimaryKey(true)
                .autoID(false)
                .build());

        schema.addField(AddFieldReq.builder()
                .fieldName("text")
                .dataType(DataType.VarChar)
                .maxLength(65535)
                .build());

        schema.addField(AddFieldReq.builder()
                .fieldName("vector")
                .dataType(DataType.FloatVector)
                .dimension(embeddingFunction.getVectorSize())
                .build());

        IndexParam indexParamForIdField = IndexParam.builder()
                .fieldName("id")
                .indexType(IndexParam.IndexType.STL_SORT)
                .build();

        IndexParam indexParamForVectorField = IndexParam.builder()
                .fieldName("vector")
                .indexType(IndexParam.IndexType.HNSW)
                .metricType(IndexParam.MetricType.COSINE)
                .build();

        List<IndexParam> indexParams = new ArrayList<>();
        indexParams.add(indexParamForIdField);
        indexParams.add(indexParamForVectorField);

        return CreateCollectionReq.builder()
                .collectionName(collectionName)
                .collectionSchema(schema)
                .indexParams(indexParams)
                .build();
    }

    protected InsertReq getInsetReq(List<List<Float>> embeddings, List<String> tests, List<Long> ids, List<Map<String, Object>> metadatas) {
        List<JsonObject> data = new ArrayList<>();
        for (int i = 0; i < embeddings.size(); i++) {
            JsonObject jsonObject = new JsonObject();
            jsonObject.addProperty("id", ids.get(i));
            jsonObject.add("vector", gson.toJsonTree(embeddings.get(i)));
            jsonObject.addProperty("text", tests.get(i));
            if (!CollectionUtils.isEmpty(metadatas)) {
                for (Map.Entry<String, Object> entry : metadatas.get(i).entrySet()) {
                    jsonObject.addProperty(entry.getKey(), entry.getValue().toString());
                }
            }
            data.add(jsonObject);
        }

        return InsertReq.builder()
                .collectionName(collectionName)
                .data(data)
                .build();
    }

    protected List<Document> getDocument(GetResp getResp) {
        if (CollectionUtils.isEmpty(getResp.getGetResults())) {
            return List.of();
        }
        return getResp.getGetResults().stream().map(getResult -> Document.builder()
                .id((Long) getResult.getEntity().get("id"))
                .pageContent((String) getResult.getEntity().get("text"))
                .build()).collect(Collectors.toList());
    }

    protected List<Document> getDocument(SearchResp searchResp) {
        if (CollectionUtils.isEmpty(searchResp.getSearchResults()) || CollectionUtils.isEmpty(searchResp.getSearchResults().get(0))) {
            return List.of();
        }
        return searchResp.getSearchResults().get(0).stream().map(searchResult -> Document.builder()
                .id((Long) searchResult.getEntity().get("id"))
                .pageContent((String) searchResult.getEntity().get("text"))
                .build()).collect(Collectors.toList());
    }

    protected List<String> getOutputFields() {
        return List.of("id", "text");
    }
}