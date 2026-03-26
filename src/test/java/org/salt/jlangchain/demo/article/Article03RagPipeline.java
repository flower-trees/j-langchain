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

package org.salt.jlangchain.demo.article;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.salt.function.flow.FlowInstance;
import org.salt.function.flow.context.ContextBus;
import org.salt.jlangchain.TestApplication;
import org.salt.jlangchain.core.BaseRunnable;
import org.salt.jlangchain.core.ChainActor;
import org.salt.jlangchain.core.llm.ollama.ChatOllama;
import org.salt.jlangchain.core.parser.StrOutputParser;
import org.salt.jlangchain.core.parser.generation.ChatGeneration;
import org.salt.jlangchain.core.prompt.string.PromptTemplate;
import org.salt.jlangchain.core.prompt.value.StringPromptValue;
import org.salt.jlangchain.rag.embedding.OllamaEmbeddings;
import org.salt.jlangchain.rag.loader.docx.ApachePoiDocxLoader;
import org.salt.jlangchain.rag.loader.pdf.PdfboxLoader;
import org.salt.jlangchain.rag.media.Document;
import org.salt.jlangchain.rag.splitter.StanfordNLPTextSplitter;
import org.salt.jlangchain.rag.vector.BaseRetriever;
import org.salt.jlangchain.rag.vector.Milvus;
import org.salt.jlangchain.rag.vector.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.junit4.SpringRunner;

import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * 文章3：用 Java 实现 RAG：从 PDF 加载到智能问答全流程
 *
 * RAG（Retrieval-Augmented Generation）流程：
 * 1. loadDocuments    - 文档加载（PDF / DOCX）
 * 2. splitDocuments   - 文本切分
 * 3. embedAndStore    - 向量化 & 存入 Milvus
 * 4. retrieveAndAsk   - 检索 + LLM 问答（完整 RAG 链）
 * 5. documentSummary  - 文档摘要（无向量库的轻量 RAG）
 */
@RunWith(SpringRunner.class)
@SpringBootTest(classes = TestApplication.class)
@SpringBootConfiguration
public class Article03RagPipeline {

    @Autowired
    ChainActor chainActor;

    static final String COLLECTION_NAME = "ArticleDemo";

    // ==================== Step 1：文档加载 ====================

    /**
     * 加载 PDF 文档
     * 支持文本提取，可选图片提取
     */
    @Test
    public void loadPdfDocuments() {
        PdfboxLoader loader = PdfboxLoader.builder()
            .filePath("./files/pdf/en/Transformer.pdf")
            .build();
        loader.setExtractImages(false);

        List<Document> documents = loader.load();

        System.out.println("=== 加载 PDF 文档 ===");
        System.out.println("总页数：" + documents.size());
        System.out.printf("第1页内容预览（前200字）：%n%s%n",
            documents.get(0).getPageContent().substring(0, Math.min(200, documents.get(0).getPageContent().length())));
    }

    /**
     * 加载 Word 文档（.docx）
     */
    @Test
    public void loadDocxDocuments() {
        ApachePoiDocxLoader loader = ApachePoiDocxLoader.builder()
            .filePath("./files/docx/sample.docx")
            .build();

        List<Document> documents = loader.load();

        System.out.println("=== 加载 DOCX 文档 ===");
        System.out.println("文档段落数：" + documents.size());
    }

    // ==================== Step 2：文本切分 ====================

    /**
     * 将长文档切分为适合 Embedding 的小块
     * chunkSize：每块最大字符数
     * chunkOverlap：相邻块的重叠字符数（保证上下文连续性）
     */
    @Test
    public void splitDocuments() {
        PdfboxLoader loader = PdfboxLoader.builder()
            .filePath("./files/pdf/en/Transformer.pdf")
            .build();
        loader.setExtractImages(false);
        List<Document> documents = loader.load();

        System.out.println("切分前：" + documents.size() + " 页");

        StanfordNLPTextSplitter splitter = StanfordNLPTextSplitter.builder()
            .chunkSize(1000)    // 每块最多 1000 字符
            .chunkOverlap(100)  // 相邻块重叠 100 字符
            .build();

        List<Document> splits = splitter.splitDocument(documents);

        System.out.println("=== 文本切分 ===");
        System.out.println("切分后：" + splits.size() + " 块");
        System.out.printf("第1块内容预览：%n%s%n", splits.get(0).getPageContent());
    }

    // ==================== Step 3：向量化并存入 Milvus ====================

    /**
     * 将文本块向量化后存入 Milvus 向量数据库
     * 需要先启动 Milvus 服务，并在 application.yml 中配置 milvus.use=true
     */
    @Test
    public void embedAndStore() {
        PdfboxLoader loader = PdfboxLoader.builder()
            .filePath("./files/pdf/en/Transformer.pdf")
            .build();
        loader.setExtractImages(false);
        List<Document> documents = loader.load();

        StanfordNLPTextSplitter splitter = StanfordNLPTextSplitter.builder()
            .chunkSize(1000).chunkOverlap(100).build();
        List<Document> splits = splitter.splitDocument(documents);

        System.out.println("开始向量化并写入 Milvus，共 " + splits.size() + " 块...");

        // OllamaEmbeddings：使用本地 Ollama 做向量化，免费且隐私安全
        VectorStore vectorStore = Milvus.fromDocuments(
            splits,
            OllamaEmbeddings.builder()
                .model("nomic-embed-text")
                .vectorSize(768)
                .build(),
            COLLECTION_NAME
        );

        System.out.println("=== 向量化完成，已写入 Milvus ===");
        System.out.println("Collection：" + COLLECTION_NAME);
    }

    // ==================== Step 4：检索 + 问答（完整 RAG 链）====================

    /**
     * 完整 RAG 流程：
     * 用户问题 → 向量检索 → 拼接上下文 → LLM 回答
     */
    @Test
    public void retrieveAndAsk() {
        // 1. 加载、切分、向量化（生产环境中这步提前做好）
        PdfboxLoader loader = PdfboxLoader.builder()
            .filePath("./files/pdf/en/Transformer.pdf")
            .build();
        loader.setExtractImages(false);
        List<Document> documents = loader.load();

        StanfordNLPTextSplitter splitter = StanfordNLPTextSplitter.builder()
            .chunkSize(1000).chunkOverlap(100).build();
        List<Document> splits = splitter.splitDocument(documents);

        VectorStore vectorStore = Milvus.fromDocuments(
            splits,
            OllamaEmbeddings.builder().model("nomic-embed-text").vectorSize(768).build(),
            COLLECTION_NAME
        );

        // 2. 创建检索器
        BaseRetriever retriever = vectorStore.asRetriever();

        // 3. 构建 RAG 问答链
        BaseRunnable<StringPromptValue, ?> prompt = PromptTemplate.fromTemplate(
            """
            请根据以下文档内容回答问题。如果文档中没有相关信息，请说"文档中未找到相关信息"。
            
            文档内容：
            ${context}
            
            问题：${question}
            
            回答：
            """
        );

        ChatOllama llm = ChatOllama.builder().model("qwen2.5:0.5b").build();

        Function<Object, String> formatDocs = input -> {
            List<Document> docs = (List<Document>) input;
            StringBuilder sb = new StringBuilder();
            for (Document doc : docs) {
                sb.append(doc.getPageContent()).append("\n\n");
            }
            return sb.toString();
        };

        FlowInstance ragChain = chainActor.builder()
            .next(retriever)              // 向量检索，返回相关文档列表
            .next(formatDocs)             // 将文档列表拼接为字符串
            .next(input -> Map.of(
                "context",  input,
                "question", ContextBus.get().getFlowParam()  // 获取原始问题
            ))
            .next(prompt)
            .next(llm)
            .next(new StrOutputParser())
            .build();

        // 4. 执行问答
        String question = "Transformer 模型中的注意力机制是如何工作的？";
        ChatGeneration result = chainActor.invoke(ragChain, question);

        System.out.println("=== RAG 问答结果 ===");
        System.out.println("问题：" + question);
        System.out.println("回答：" + result.getText());
    }

    // ==================== Step 5：文档摘要（轻量 RAG）====================

    /**
     * 无需向量库的文档摘要
     * 适合文档较短或只需要摘要的场景
     */
    @Test
    public void documentSummary() {
        PdfboxLoader loader = PdfboxLoader.builder()
            .filePath("./files/pdf/en/Transformer.pdf")
            .build();
        loader.setExtractImages(false);
        List<Document> documents = loader.load();

        // 拼接所有页面内容（截取前后各1000字，避免超出 context window）
        StringBuilder contentBuilder = new StringBuilder();
        for (Document doc : documents) {
            contentBuilder.append(doc.getPageContent()).append("\n");
        }
        String content = contentBuilder.toString();

        String textToSummarize;
        if (content.length() < 2000) {
            textToSummarize = content;
        } else {
            textToSummarize = content.substring(0, 1000) + "\n\n...\n\n" + content.substring(content.length() - 1000);
        }

        BaseRunnable<StringPromptValue, ?> prompt = PromptTemplate.fromTemplate(
            """
            请对以下文档内容进行摘要（100字以内）：
            
            ${text}
            
            摘要：
            """
        );

        ChatOllama llm = ChatOllama.builder().model("qwen2.5:0.5b").build();
        FlowInstance chain = chainActor.builder().next(prompt).next(llm).next(new StrOutputParser()).build();

        ChatGeneration result = chainActor.invoke(chain, Map.of("text", textToSummarize));

        System.out.println("=== 文档摘要 ===");
        System.out.println(result.getText());
    }
}
