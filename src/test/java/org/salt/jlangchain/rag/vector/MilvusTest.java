package org.salt.jlangchain.rag.vector;

import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.salt.jlangchain.TestApplication;
import org.salt.jlangchain.rag.embedding.OllamaEmbeddings;
import org.salt.jlangchain.rag.media.Document;
import org.salt.jlangchain.utils.JsonUtil;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.junit4.SpringRunner;

import java.util.List;
import java.util.Map;

@RunWith(SpringRunner.class)
@SpringBootTest(classes = TestApplication.class)
@SpringBootConfiguration
public class MilvusTest {

    Milvus milvus;
    String collectionName = "JLangChainTest";

    @Before
    public void init() {
        milvus = new Milvus(collectionName, OllamaEmbeddings.builder().model("nomic-embed-text").vectorSize(768).build());
    }

    @Test
    public void addText() {
        List<Long> ids = milvus.addText(List.of("hello world", "how are you"), List.of(Map.of(), Map.of()), List.of(), 1L);
        System.out.println(JsonUtil.toJson(ids));
        Assert.assertFalse(ids.isEmpty());
    }

    @Test
    public void addDocument() {
        List<Long> ids = milvus.addDocument(List.of(Document.builder().pageContent("hello world").build(), Document.builder().pageContent("how are you").build()), 1L);
        System.out.println(JsonUtil.toJson(ids));
        Assert.assertFalse(ids.isEmpty());
    }

    @Test
    public void delete() {
        System.out.println(JsonUtil.toJson(milvus.delete(List.of(1892830942258069504L))));
    }

    @Test
    public void getByIds() {
        System.out.println(JsonUtil.toJson(milvus.getByIds(List.of(1892827947864113152L))));
    }

    @Test
    public void similaritySearch() {
        List<Document> documents = milvus.similaritySearch("who are you", 1);
        System.out.println(JsonUtil.toJson(documents));
        Assert.assertFalse(documents.isEmpty());
    }

    @Test
    public void vectorStoreRetriever() {
        BaseRetriever baseRetriever = milvus.asRetriever();
        List<Document> documents = baseRetriever.invoke("hello world");
        System.out.println(JsonUtil.toJson(documents));
        Assert.assertFalse(documents.isEmpty());
    }

    @Test
    public void fromText() {
        VectorStore vectorStore = Milvus.fromText(
                List.of("hello world", "how are you"),
                OllamaEmbeddings.builder().model("nomic-embed-text").vectorSize(768).build(),
                collectionName);
        List<Document> documents = vectorStore.similaritySearch("who are you", 1);
        System.out.println(JsonUtil.toJson(documents));
        Assert.assertFalse(documents.isEmpty());
    }
}