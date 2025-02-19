package org.salt.jlangchain.rag.embedding;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.salt.jlangchain.TestApplication;
import org.salt.jlangchain.utils.JsonUtil;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.junit4.SpringRunner;

import java.util.List;

@RunWith(SpringRunner.class)
@SpringBootTest(classes = TestApplication.class)
@SpringBootConfiguration
public class OllamaEmbeddingsTest {

    @Test
    public void testEmbedDocuments() {
        OllamaEmbeddings ollamaEmbeddings = new OllamaEmbeddings();
        List<List<Double>> embeddings = ollamaEmbeddings.embedDocuments(List.of("This is a test sentence.", "How are you?"));
        System.out.println(JsonUtil.toJson(embeddings));
    }

    @Test
    public void testEmbedQuery() {
        OllamaEmbeddings ollamaEmbeddings = new OllamaEmbeddings();
        List<List<Double>> embeddings = ollamaEmbeddings.embedDocuments(List.of("This is a test sentence."));
        System.out.println(JsonUtil.toJson(embeddings));
    }
}