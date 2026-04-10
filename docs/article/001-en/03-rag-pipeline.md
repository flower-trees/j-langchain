# Implementing RAG in Java: From PDF Loading to Intelligent Q&A

> **Audience**: Java developers building knowledge-base assistants  
> **Key tech**: RAG, Milvus vector DB, text embeddings

---

## What Is RAG?

**RAG (Retrieval-Augmented Generation)** is today’s mainstream enterprise AI pattern.

Problem statement: LLMs have training cut-off dates and never see your proprietary data (internal docs, manuals, policies). RAG solves this by:

```
User question → retrieve matching passages from your private corpus → send passages + question to the LLM → receive an answer grounded in your data
```

**Benefits**
- No fine-tuning required, very low cost
- Knowledge base can be updated at any time and takes effect immediately
- Source tracing: responses can cite the originating document

---

## Complete RAG Pipeline

```
PDF/Word docs
     ↓
[Loader] PdfboxLoader / ApachePoiDocxLoader
     ↓
[Text splitter] StanfordNLPTextSplitter
     ↓
[Embedding model] OllamaEmbeddings
     ↓
[Vector DB] Milvus (storage)
     ↓ (during query time)
User question → [Vector retrieval] → relevant chunks → [LLM] → final answer
```

---

## Prerequisites

The pipeline depends on Milvus and the Tesseract OCR service. Enable them explicitly in `application.yml`:

```yaml
rag:
  ocr:
    tesseract:
      use: true   # enable Tesseract OCR for PDF images
  vector:
    milvus:
      use: true   # enable the Milvus vector database
```

> **Note**: j-langchain uses `@ConditionalOnProperty` to guard these beans. They are not loaded unless `use: true` is set, so `TesseractActuator` and `MilvusContainer` only exist then. Calling RAG code without the flags results in missing-bean errors.

---

## Step 1: Load Documents

j-langchain ships several document loaders.

### Load PDF

```java
@Test
public void loadPdfDocuments() {
    PdfboxLoader loader = PdfboxLoader.builder()
        .filePath("./files/pdf/en/Transformer.pdf")
        .build();
    loader.setExtractImages(false);

    List<Document> documents = loader.load();

    System.out.println("页数：" + documents.size());
    // Each Document corresponds to one PDF page
}
```

### Load Word

```java
ApachePoiDocxLoader loader = ApachePoiDocxLoader.builder()
    .filePath("./files/docx/en/Transformer.docx")
    .build();

List<Document> documents = loader.load();
```

Each `Document` contains:
- `pageContent`: the text
- `metadata`: source, page number, etc.

---

## Step 2: Split Text

A single PDF page can contain thousands of characters; embedding long chunks hurts recall and may exceed the LLM context window. Split large docs into smaller ones:

```java
@Test
public void splitDocuments() {
    List<Document> documents = loader.load();
    System.out.println("Before split: " + documents.size());

    StanfordNLPTextSplitter splitter = StanfordNLPTextSplitter.builder()
        .chunkSize(1000)
        .chunkOverlap(100)
        .build();

    List<Document> splits = splitter.splitDocument(documents);
    System.out.println("After split: " + splits.size());
}
```

**Why overlap?** If a sentence crosses two chunks, the overlapped portion ensures the full sentence exists in at least one chunk, preventing semantic breaks.

---

## Step 3: Embed and Store in Milvus

Convert every chunk to a vector (array of floats) and save it into the vector DB:

```java
@Test
public void embedAndStore() {
    VectorStore vectorStore = Milvus.fromDocuments(
        splits,
        OllamaEmbeddings.builder()
            .model("nomic-embed-text")
            .vectorSize(768)
            .build(),
        "MyKnowledgeBase"
    );

    System.out.println("Embedding done!");
}
```

**Why local embeddings?** `nomic-embed-text` is a high-quality open-source model that runs locally via Ollama:
- Free: no OpenAI calls
- Private: data never leaves the machine
- Strong bilingual performance

**Start Milvus** (one-line Docker):

```bash
docker run -d --name milvus \
  -p 19530:19530 \
  milvusdb/milvus:latest standalone
```

---

## Step 4: Full RAG QA Chain

Retrieve relevant chunks for the user question, stitch them into the prompt, and ask the LLM:

```java
@Test
public void retrieveAndAsk() {
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
        .next(retriever)
        .next(formatDocs)
        .next(input -> Map.of(
            "context",  input,
            "question", ContextBus.get().getFlowParam()
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

**Flow explanation**
```
Question → retriever (return top-N chunks)
         → formatDocs (merge chunks)
         → prompt (context + question)
         → LLM → StrOutputParser
         → Final answer
```

---

## Step 5: Document Summary (Lightweight)

Need only a summary? Let the LLM read a truncated document:

```java
@Test
public void documentSummary() {
    List<Document> documents = loader.load();
    String content = documents.stream()
        .map(Document::getPageContent)
        .collect(Collectors.joining("\n"));

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

## RAG vs. Direct LLM Queries

| Item | Direct LLM | RAG |
|------|------------|-----|
| Private knowledge | Missing | Present (from your docs) |
| Freshness | Fixed at training cutoff | Real-time updates |
| Traceability | None | Yes (return sources) |
| Cost | Low | Slightly higher (embeddings + vector DB) |
| Hallucinations | High risk | Much lower |

---

## Architecture

```
Offline build:
Documents → Load → Split → Embed → Milvus

Online query:
Question → Embed → Milvus search → Assemble context → LLM → Answer
```

---

> Full code: `src/test/java/org/salt/jlangchain/demo/article/Article03RagPipeline.java`
