package org.salt.jlangchain.rag.loader.pdf;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.salt.jlangchain.TestApplication;
import org.salt.jlangchain.core.common.Iterator;
import org.salt.jlangchain.rag.loader.media.Document;
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

        PdfboxLoader loader = new PdfboxLoader();
        loader.setFilePath("/Users/chuanzhizhu/Desktop/1780761576456134656.pdf");
        loader.setExtractImages(false);
        List<Document> documents = loader.load();
        for (Document document : documents) {
            System.out.println(document.getPageContent());
        }
    }

    @Test
    public void testLazyLoad() throws TimeoutException {

        PdfboxLoader loader = new PdfboxLoader();
        loader.setFilePath("/Users/chuanzhizhu/Desktop/1788758958499336192.pdf");
        loader.setExtractImages(true);
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