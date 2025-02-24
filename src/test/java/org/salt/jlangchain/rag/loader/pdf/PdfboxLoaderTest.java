package org.salt.jlangchain.rag.loader.pdf;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.salt.jlangchain.TestApplication;
import org.salt.jlangchain.core.common.Iterator;
import org.salt.jlangchain.rag.media.Document;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.junit4.SpringRunner;

import java.util.List;
import java.util.concurrent.TimeoutException;


@RunWith(SpringRunner.class)
@SpringBootTest(classes = TestApplication.class)
@SpringBootConfiguration
public class PdfboxLoaderTest {

    @Test
    public void testLoad() {

        PdfboxLoader loader = PdfboxLoader.builder()
                .filePath("./files/pdf/en/Transformer.pdf")
                .extractImages(true)
                .build();
        List<Document> documents = loader.load();
        for (Document document : documents) {
            System.out.println(document.getPageContent());
        }
    }

    @Test
    public void testLazyLoad() throws TimeoutException {

        PdfboxLoader loader = PdfboxLoader.builder()
                .filePath("./files/pdf/en/Transformer.pdf")
                .extractImages(true)
                .build();
        Iterator<Document> iterator = loader.lazyLoad();
        while (iterator.hasNext()) {
            try {
                Document document = iterator.next();
                System.out.println(document.getPageContent());
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
    }
}