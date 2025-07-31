package org.salt.jlangchain.rag.loader.docx;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.salt.jlangchain.TestApplication;
import org.salt.jlangchain.core.common.Iterator;
import org.salt.jlangchain.rag.loader.doc.ApachePoiDocLoader;
import org.salt.jlangchain.rag.media.Document;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.junit4.SpringRunner;

import java.util.List;
import java.util.concurrent.TimeoutException;


@RunWith(SpringRunner.class)
@SpringBootTest(classes = TestApplication.class)
@SpringBootConfiguration
public class ApachePoiDocLoaderTest {

    @Test
    public void testLoad() {

        ApachePoiDocLoader loader = ApachePoiDocLoader.builder()
                .filePath("./files/doc/cn/掌控物联网与人工智能.doc")
                .extractImages(false)
                .build();
        List<Document> documents = loader.load();
        for (Document document : documents) {
            System.out.println(document.getPageContent());
            System.out.println("\n----------------------\n");
        }
    }

    @Test
    public void testLazyLoad() throws TimeoutException {

        ApachePoiDocLoader loader = ApachePoiDocLoader.builder()
                .filePath("./files/doc/cn/掌控物联网与人工智能.doc")
                .extractImages(false)
                .build();
        Iterator<Document> iterator = loader.lazyLoad();
        while (iterator.hasNext()) {
            try {
                Document document = iterator.next();
                System.out.println(document.getPageContent());
                System.out.println("\n----------------------\n");
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
    }
}