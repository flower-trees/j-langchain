# Implementing RAG in Java: From PDF Loading to Intelligent Q&A

> **Audience**: Java developers who need to build knowledge-base Q&A systems  
> **Core technologies**: RAG, Milvus vector database, text Embedding

---

## What Is RAG?

**RAG (Retrieval-Augmented Generation)** is currently the most mainstream enterprise AI application pattern.

The problem it solves: large models have a training data cutoff date and do not contain your private data (internal documents, product manuals, company policies, etc.). The RAG approach is:

```
User question → Search private knowledge base for relevant content → Send retrieved content + question to LLM → Get an answer grounded in private knowledge
```

**Advantages**:
- No model fine-tuning required — extremely low cost
- The knowledge base can be updated at any time and takes effect immediately
- Traceable — can tell the user which document the answer came from

---

## Complete RAG Pipeline

```
PDF / Word documents
     ↓
[Document Loader] PdfboxLoader / ApachePoiDocxLoader
     ↓
[Text Splitter] StanfordNLPTextSplitter
     ↓
[Embedding Model] OllamaEmbeddings
     ↓
[Vector Database] Milvus (storage)
     ↓ (at query time)
User question → [Vector Search] → Relevant chunks → [LLM] → Final answer
```

---

## Prerequisites

The RAG pipeline depends on the Milvus vector database and Tesseract OCR, which must be explicitly enabled in `application.yml`:

```yaml
rag:
  ocr:
    tesseract:
      use: true   # Enable Tesseract OCR (for text extraction from PDF images)
  vector:
    milvus:
      use: true   # Enable Milvus vector database
```

> **Note**: j-langchain uses `@ConditionalOnProperty` to control the initialization of these two components, which are not loaded by default. Only after setting `use: true` will the `TesseractActuator` and `MilvusContainer` beans be registered in the Spring container. Running RAG-related code without enabling them will result in missing-bean errors.

---

## Step 1: Load Documents

j-langchain includes several built-in document loaders:

### Load a PDF

```java
@Test
public void loadPdfDocuments() {
    PdfboxLoader loader = PdfboxLoader.builder()
        .filePath("./files/pdf/en/Transformer.pdf")
        .build();
    loader.setExtractImages(false);  // Extract text only, not images

    List<Document> documents = loader.load();

    System.out.println("Total pages: " + documents.size());
    // Each Document corresponds to one page of the PDF
}
```

### Load a Word Document

```java
ApachePoiDocxLoader loader = ApachePoiDocxLoader.builder()
    .filePath("./files/docx/en/Transformer.docx")
    .build();

List<Document> documents = loader.load();
```

Each `Document` object contains:
- `pageContent`: the text content of the page
- `metadata`: source, page number, and other metadata

---

## Step 2: Split the Text

A single PDF page may contain thousands of characters. Embedding an entire page produces poor results and may exceed the LLM's context window. Split long documents into smaller chunks:

```java
@Test
public void splitDocuments() {
    List<Document> documents = loader.load();
    System.out.println("Before splitting: " + documents.size() + " pages");

    StanfordNLPTextSplitter splitter = StanfordNLPTextSplitter.builder()
        .chunkSize(1000)    // Maximum 1000 characters per chunk
        .chunkOverlap(100)  // 100-character overlap between adjacent chunks to preserve context
        .build();

    List<Document> splits = splitter.splitDocument(documents);
    System.out.println("After splitting: " + splits.size() + " chunks");
}
```

**Why is `chunkOverlap` needed?**

If a sentence spans two chunks, the overlapping region ensures the sentence appears completely in at least one chunk, preventing semantic truncation.

---

## Step 3: Embed and Store in Milvus

Convert each text chunk into a vector (an array of floats) and store it in the vector database:

```java
@Test
public void embedAndStore() {
    // ... load and split documents ...

    VectorStore vectorStore = Milvus.fromDocuments(
        splits,
        OllamaEmbeddings.builder()
            .model("nomic-embed-text")  // Local embedding model — free
            .vectorSize(768)            // Vector dimension
            .build(),
        "MyKnowledgeBase"               // Milvus collection name
    );

    System.out.println("Embedding complete!");
}
```

**Why use a local embedding model?**

`nomic-embed-text` is a high-quality open-source embedding model that runs locally via Ollama:
- Zero cost: no OpenAI API calls needed
- Privacy-safe: data never leaves your machine
- High quality: excellent performance on both Chinese and English embedding tasks

**Start Milvus** (one-command Docker launch):
```bash
docker run -d --name milvus \
  -p 19530:19530 \
  milvusdb/milvus:latest standalone
```

---

## Step 4: The Complete RAG Q&A Chain

This is the core step: retrieve relevant document chunks using the user's question, assemble the context, and let the LLM answer based on that context:

```java
@Test
public void retrieveAndAsk() {
    // Assumes documents have already been stored in Milvus...
    BaseRetriever retriever = vectorStore.asRetriever();

    BaseRunnable<StringPromptValue, ?> prompt = PromptTemplate.fromTemplate(
        """
        Answer the question based on the following document content.
        If the document does not contain relevant information, say "No relevant information found in the document."
        
        Document content:
        ${context}
        
        Question: ${question}
        
        Answer:
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
        .next(retriever)   // Vector search: takes the question, returns relevant document chunks
        .next(formatDocs)  // Concatenate the document list into a single string
        .next(input -> Map.of(
            "context",  input,
            "question", ContextBus.get().getFlowParam()  // Retrieve the original question
        ))
        .next(prompt)
        .next(llm)
        .next(new StrOutputParser())
        .build();

    ChatGeneration result = chainActor.invoke(
        ragChain,
        "How does the attention mechanism work in the Transformer model?"
    );

    System.out.println(result.getText());
}
```

**Chain diagram**:
```
"How does the attention mechanism work..."
    → retriever (similarity search, returns the 5 most relevant chunks)
    → formatDocs (concatenate chunks into a string)
    → prompt (assemble Prompt: context + question)
    → LLM (generate answer based on context)
    → StrOutputParser (extract text)
    → "The attention mechanism works by computing Query, Key, Value..."
```

---

## Step 5: Document Summarization (Lightweight)

For simple scenarios that don't require a vector database — just have the LLM summarize the document directly:

```java
@Test
public void documentSummary() {
    // Load the PDF
    List<Document> documents = loader.load();
    String content = documents.stream()
        .map(Document::getPageContent)
        .collect(Collectors.joining("\n"));

    // Truncate long documents to beginning and end
    String textToSummarize = content.length() < 2000 ? content
        : content.substring(0, 1000) + "\n...\n" + content.substring(content.length() - 1000);

    FlowInstance chain = chainActor.builder()
        .next(PromptTemplate.fromTemplate("Summarize the following content in under 100 words:\n\n${text}"))
        .next(ChatOllama.builder().model("qwen2.5:0.5b").build())
        .next(new StrOutputParser())
        .build();

    ChatGeneration result = chainActor.invoke(chain, Map.of("text", textToSummarize));
    System.out.println(result.getText());
}
```

---

## RAG vs. Asking the LLM Directly

| Dimension | Direct LLM | RAG |
|-----------|-----------|-----|
| Private knowledge | Unknown | Known (from your documents) |
| Knowledge freshness | Training cutoff | Real-time updates |
| Traceable answers | No | Yes (returns source document) |
| Cost | Low | Slightly higher (Embedding + vector DB) |
| Hallucination risk | High | Low (grounded in real documents) |

---

## Complete Architecture

```
Offline phase (building the index):
Documents → Load → Split → Embed → Milvus

Online phase (Q&A):
Question → Embed → Milvus search → Assemble context → LLM → Answer
```

---

> Full source code: `src/test/java/org/salt/jlangchain/demo/article/Article03RagPipeline.java`
