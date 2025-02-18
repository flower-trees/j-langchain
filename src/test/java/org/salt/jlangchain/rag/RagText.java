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

package org.salt.jlangchain.rag;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.salt.function.flow.FlowInstance;
import org.salt.jlangchain.TestApplication;
import org.salt.jlangchain.core.BaseRunnable;
import org.salt.jlangchain.core.ChainActor;
import org.salt.jlangchain.core.llm.ollama.ChatOllama;
import org.salt.jlangchain.core.parser.StrOutputParser;
import org.salt.jlangchain.core.parser.generation.ChatGeneration;
import org.salt.jlangchain.core.prompt.string.PromptTemplate;
import org.salt.jlangchain.core.prompt.value.StringPromptValue;
import org.salt.jlangchain.rag.media.Document;
import org.salt.jlangchain.rag.loader.pdf.PdfboxLoader;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.junit4.SpringRunner;

import java.util.List;
import java.util.Map;

@RunWith(SpringRunner.class)
@SpringBootTest(classes = TestApplication.class)
@SpringBootConfiguration
public class RagText {

    @Autowired
    ChainActor chainActor;

    @Test
    public void pdfSummaryTest() {

        PdfboxLoader loader = new PdfboxLoader();
        loader.setFilePath("/Users/chuanzhizhu/Desktop/1780761576456134656.pdf");
        loader.setExtractImages(false);
        List<Document> documents = loader.load();

        StringBuilder contentBuilder = new StringBuilder();
        for (Document doc : documents) {
            contentBuilder.append(doc.getPageContent()).append("\n");  // 合并并添加换行符
        }
        String content = contentBuilder.toString();

        int contentLength = content.length();

        String textToSummarize;
        if (contentLength < 2000) {
            textToSummarize = content;
        } else {
            String startText = content.substring(0, 1000);
            String endText = content.substring(content.length() - 1000);
            textToSummarize = startText + "\n\n" + endText;
        }

        String promptTemplate = """
                请对以下文本进行总结（100个字以内）：
                
                ${text}
                
                总结：
                """;

        BaseRunnable<StringPromptValue, ?> prompt = PromptTemplate.fromTemplate(promptTemplate);
        ChatOllama llm = ChatOllama.builder().model("deepseek-r1:7b").build();

        FlowInstance chain = chainActor.builder().next(prompt).next(llm).next(new StrOutputParser()).build();

        ChatGeneration result = chainActor.invoke(chain, Map.of("text", textToSummarize));
        System.out.println(result);
    }
}