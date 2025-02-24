package org.salt.jlangchain.rag.splitter;

import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.salt.jlangchain.TestApplication;
import org.salt.jlangchain.rag.loader.pdf.PdfboxLoader;
import org.salt.jlangchain.rag.media.Document;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.junit4.SpringRunner;

import java.util.List;
import java.util.stream.Collectors;

@RunWith(SpringRunner.class)
@SpringBootTest(classes = TestApplication.class)
@SpringBootConfiguration
public class StanfordNLPSplitterTest {

    @Test
    public void testSplitText() {

        PdfboxLoader loader = PdfboxLoader.builder()
                .filePath("./files/pdf/en/Transformer.pdf")
                .build();
        List<Document> documents = loader.load();
        String text = documents.stream()
                .map(Document::getPageContent)
                .collect(Collectors.joining("\n\n"));

        StanfordNLPTextSplitter splitter = StanfordNLPTextSplitter.builder().chunkSize(2000).chunkOverlap(200).build();
        List<String> splitText = splitter.splitText(text);
        splitText.forEach(split -> System.out.println("Split:" + split));
        Assert.assertFalse(splitText.isEmpty());
    }

    @Test
    public void testSplitDocument() {
        PdfboxLoader loader = PdfboxLoader.builder()
                .filePath("./files/pdf/en/Transformer.pdf")
                .build();
        List<Document> documents = loader.load();
        StanfordNLPTextSplitter splitter = StanfordNLPTextSplitter.builder().chunkSize(2000).chunkOverlap(200).build();
        List<Document> splitDocument = splitter.splitDocument(documents);
        splitDocument.forEach(split -> System.out.println("Split:" + split.getPageContent()));
        Assert.assertFalse(splitDocument.isEmpty());
    }

    @Test
    public void testSplitDocumentInPage() {
        PdfboxLoader loader = PdfboxLoader.builder()
                .filePath("./files/pdf/en/Transformer.pdf")
                .build();
        List<Document> documents = loader.load();
        StanfordNLPTextSplitter splitter = StanfordNLPTextSplitter.builder().chunkSize(2000).chunkOverlap(200).build();
        List<Document> splitDocument = splitter.splitDocumentInPage(documents);
        splitDocument.forEach(split -> System.out.println("Split:" + split.getPageContent()));
        Assert.assertFalse(splitDocument.isEmpty());
    }
}