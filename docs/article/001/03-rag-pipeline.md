# 用 Java 实现 RAG：从 PDF 加载到智能问答全流程

> **适合人群**：需要构建知识库问答系统的 Java 开发者  
> **核心技术**：RAG、向量数据库 Milvus、文本 Embedding

---

## 什么是 RAG？

**RAG（Retrieval-Augmented Generation，检索增强生成）** 是目前最主流的企业 AI 应用模式。

解决的问题：大模型的训练数据有截止日期，且不包含你的私有数据（内部文档、产品手册、规章制度等）。RAG 的思路是：

```
用户提问 → 在私有知识库中检索相关内容 → 将检索结果 + 问题一起发给 LLM → 得到基于私有知识的回答
```

**优势**：
- 无需微调模型，成本极低
- 知识库可随时更新，实时生效
- 可溯源，能告诉用户答案来自哪个文档

---

## 完整 RAG 流程

```
PDF/Word文档
     ↓
[文档加载器] PdfboxLoader / ApachePoiDocxLoader
     ↓
[文本切分器] StanfordNLPTextSplitter
     ↓
[Embedding 模型] OllamaEmbeddings
     ↓
[向量数据库] Milvus（存储）
     ↓ （查询时）
用户问题 → [向量检索] → 相关文档块 → [LLM] → 最终回答
```

---

## 前置配置

RAG Pipeline 依赖 Milvus 向量数据库和 Tesseract OCR，需要在 `application.yml` 中显式开启：

```yaml
rag:
  ocr:
    tesseract:
      use: true   # 启用 Tesseract OCR（PDF 图片文字识别）
  vector:
    milvus:
      use: true   # 启用 Milvus 向量数据库
```

> **说明**：j-langchain 通过 `@ConditionalOnProperty` 控制这两个组件的初始化，默认不加载。只有配置 `use: true` 后，`TesseractActuator` 和 `MilvusContainer` Bean 才会被注册到 Spring 容器中。未开启时运行 RAG 相关代码会因 Bean 缺失而报错。

---

## Step 1：加载文档

j-langchain 内置多种文档加载器：

### 加载 PDF

```java
@Test
public void loadPdfDocuments() {
    PdfboxLoader loader = PdfboxLoader.builder()
        .filePath("./files/pdf/en/Transformer.pdf")
        .build();
    loader.setExtractImages(false);  // 不提取图片，只提取文本

    List<Document> documents = loader.load();

    System.out.println("总页数：" + documents.size());
    // 每个 Document 对应 PDF 的一页
}
```

### 加载 Word 文档

```java
ApachePoiDocxLoader loader = ApachePoiDocxLoader.builder()
    .filePath("./files/docx/en/Transformer.docx")
    .build();

List<Document> documents = loader.load();
```

每个 `Document` 对象包含：
- `pageContent`：页面文本内容
- `metadata`：来源、页码等元数据

---

## Step 2：文本切分

PDF 文档一页可能有几千字，直接 Embedding 效果差，而且超出 LLM 的 context window。需要将长文档切分为小块：

```java
@Test
public void splitDocuments() {
    List<Document> documents = loader.load();
    System.out.println("切分前：" + documents.size() + " 页");

    StanfordNLPTextSplitter splitter = StanfordNLPTextSplitter.builder()
        .chunkSize(1000)    // 每块最多 1000 字符
        .chunkOverlap(100)  // 相邻块重叠 100 字符，保证上下文连贯
        .build();

    List<Document> splits = splitter.splitDocument(documents);
    System.out.println("切分后：" + splits.size() + " 块");
}
```

**为什么需要 chunkOverlap？**

如果一句话跨两个块，重叠部分确保这句话完整出现在至少一个块中，避免语义截断。

---

## Step 3：向量化并存入 Milvus

将每个文本块转换为向量（浮点数数组），存入向量数据库：

```java
@Test
public void embedAndStore() {
    // ... 加载、切分文档 ...

    VectorStore vectorStore = Milvus.fromDocuments(
        splits,
        OllamaEmbeddings.builder()
            .model("nomic-embed-text")  // 本地 Embedding 模型，免费
            .vectorSize(768)            // 向量维度
            .build(),
        "MyKnowledgeBase"               // Milvus collection 名称
    );

    System.out.println("向量化完成！");
}
```

**为什么用本地 Embedding？**

`nomic-embed-text` 是一个高质量的开源 Embedding 模型，通过 Ollama 本地运行：
- 零成本：不需要调 OpenAI API
- 隐私安全：数据不出本地
- 效果好：在中英文 Embedding 上表现优秀

**启动 Milvus**（Docker 一键启动）：
```bash
docker run -d --name milvus \
  -p 19530:19530 \
  milvusdb/milvus:latest standalone
```

---

## Step 4：完整 RAG 问答链

这是核心步骤：用用户的问题检索相关文档块，拼接上下文，让 LLM 基于上下文回答：

```java
@Test
public void retrieveAndAsk() {
    // 假设文档已经存入 Milvus...
    BaseRetriever retriever = vectorStore.asRetriever();

    BaseRunnable<StringPromptValue, ?> prompt = PromptTemplate.fromTemplate(
        """
        请根据以下文档内容回答问题。如果文档中没有相关信息，请说"文档中未找到相关信息"。
        
        文档内容：
        ${context}
        
        问题：${question}
        
        回答：
        """
    );

    Function<Object, String> formatDocs = input -> {
        List<Document> docs = (List<Document>) input;
        StringBuilder sb = new StringBuilder();
        for (Document doc : docs) {
            sb.append(doc.getPageContent()).append("\n\n");
        }
        return sb.toString();
    };

    FlowInstance ragChain = chainActor.builder()
        .next(retriever)   // 向量检索：输入问题，返回相关文档列表
        .next(formatDocs)  // 将文档列表拼接为字符串
        .next(input -> Map.of(
            "context",  input,
            "question", ContextBus.get().getFlowParam()  // 获取原始问题
        ))
        .next(prompt)
        .next(llm)
        .next(new StrOutputParser())
        .build();

    ChatGeneration result = chainActor.invoke(
        ragChain,
        "Transformer 模型中的注意力机制是如何工作的？"
    );

    System.out.println(result.getText());
}
```

**链路图解**：
```
"Transformer注意力机制..." 
    → retriever（相似度检索，返回最相关的5个文档块）
    → formatDocs（拼接文档块为字符串）
    → prompt（组装 Prompt：上下文 + 问题）
    → LLM（基于上下文生成回答）
    → StrOutputParser（提取文本）
    → "注意力机制通过计算 Query、Key、Value..."
```

---

## Step 5：文档摘要（轻量版）

不需要向量库的简单场景——直接让 LLM 读文档摘要：

```java
@Test
public void documentSummary() {
    // 加载 PDF
    List<Document> documents = loader.load();
    String content = documents.stream()
        .map(Document::getPageContent)
        .collect(Collectors.joining("\n"));

    // 长文档截取首尾
    String textToSummarize = content.length() < 2000 ? content
        : content.substring(0, 1000) + "\n...\n" + content.substring(content.length() - 1000);

    FlowInstance chain = chainActor.builder()
        .next(PromptTemplate.fromTemplate("请对以下内容摘要（100字以内）：\n\n${text}"))
        .next(ChatOllama.builder().model("qwen2.5:0.5b").build())
        .next(new StrOutputParser())
        .build();

    ChatGeneration result = chainActor.invoke(chain, Map.of("text", textToSummarize));
    System.out.println(result.getText());
}
```

---

## RAG vs 直接问 LLM

| 对比项 | 直接问 LLM | RAG |
|--------|-----------|-----|
| 私有知识 | 不知道 | 知道（来自你的文档） |
| 知识时效性 | 训练截止日期 | 实时更新 |
| 回答可溯源 | 不行 | 可以（返回来源文档） |
| 成本 | 低 | 稍高（Embedding + 向量库） |
| 幻觉风险 | 高 | 低（基于真实文档） |

---

## 完整架构

```
离线阶段（建库）：
文档 → 加载 → 切分 → Embedding → Milvus

在线阶段（问答）：
问题 → Embedding → Milvus检索 → 拼接上下文 → LLM → 回答
```

---

> 完整代码见：`src/test/java/org/salt/jlangchain/demo/article/Article03RagPipeline.java`
