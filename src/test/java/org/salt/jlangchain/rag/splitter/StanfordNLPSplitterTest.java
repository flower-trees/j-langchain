package org.salt.jlangchain.rag.splitter;

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

        PdfboxLoader loader = new PdfboxLoader();
        loader.setFilePath("/Users/chuanzhizhu/Desktop/1780761576456134656.pdf");
        List<Document> documents = loader.load();
        String text = documents.stream()
                .map(Document::getPageContent)
                .collect(Collectors.joining("\n\n"));

        StanfordNLPTestSplitter stanfordNLPTestSplitter = new StanfordNLPTestSplitter();
        List<String> splitText = stanfordNLPTestSplitter.splitText(text);
        splitText.forEach(split -> System.out.println("Split:" + split));
    }

    @Test
    public void testSplitDocument() {
        PdfboxLoader loader = new PdfboxLoader();
        loader.setFilePath("/Users/chuanzhizhu/Desktop/1780761576456134656.pdf");
        List<Document> documents = loader.load();
        StanfordNLPTestSplitter stanfordNLPTestSplitter = new StanfordNLPTestSplitter();
        List<Document> splitDocument = stanfordNLPTestSplitter.splitDocument(documents);
        splitDocument.forEach(split -> System.out.println("Split:" + split.getPageContent()));
    }

    @Test
    public void testSplitDocumentInPage() {
        PdfboxLoader loader = new PdfboxLoader();
        loader.setFilePath("/Users/chuanzhizhu/Desktop/1780761576456134656.pdf");
        List<Document> documents = loader.load();
        StanfordNLPTestSplitter stanfordNLPTestSplitter = new StanfordNLPTestSplitter();
        List<Document> splitDocument = stanfordNLPTestSplitter.splitDocumentInPage(documents);
        splitDocument.forEach(split -> System.out.println("Split:" + split.getPageContent()));
    }
}