# J-LangChain Quick Start

Get up and running in 5 minutes. For more examples, see [README Core Capabilities](../../README.md#-core-capabilities) and [MyFirstAIApp source](../../src/test/java/org/salt/jlangchain/demo/flow/MyFirstAIApp.java).

---

## 1. Add Dependency

```xml
<dependency>
    <groupId>io.github.flower-trees</groupId>
    <artifactId>j-langchain</artifactId>
    <version>1.0.14</version>
</dependency>
```

## 2. Configure

```java
@SpringBootApplication
@Import(JLangchainConfig.class)
public class Application {
    public static void main(String[] args) {
        SpringApplication.run(Application.class, args);
    }
}
```

```bash
export ALIYUN_KEY=sk-xxx...   # Alibaba Cloud Qwen (used in this tutorial)
```

## 3. Your First AI Application

```java
@Autowired ChainActor chainActor;

public void hello() {
    PromptTemplate prompt = PromptTemplate.fromTemplate("Tell me a joke about ${topic}");
    ChatAliyun llm = ChatAliyun.builder().model("qwen-plus").build();

    FlowInstance chain = chainActor.builder()
        .next(prompt)
        .next(llm)
        .next(new StrOutputParser())
        .build();

    ChatGeneration result = chainActor.invoke(chain, Map.of("topic", "programmers"));
    System.out.println(result.getText());
}
```

## 4. Run

```bash
export ALIYUN_KEY=your-key
mvn test -Dtest=MyFirstAIApp#hello
```

---

## 5. RAG Document Q&A

Build a document Q&A assistant: load PDF → split → embed → store in Milvus → retrieve + LLM answer.

```java
// 1. Load and split document
PdfboxLoader loader = PdfboxLoader.builder().filePath("path/to/document.pdf").build();
List<Document> docs = loader.load();
StanfordNLPTextSplitter splitter = StanfordNLPTextSplitter.builder().chunkSize(500).chunkOverlap(50).build();
List<Document> chunks = splitter.splitDocument(docs);

// 2. Vector store (requires Milvus + Ollama)
OllamaEmbeddings embeddings = OllamaEmbeddings.builder().model("nomic-embed-text").vectorSize(768).build();
VectorStore vectorStore = Milvus.fromDocuments(chunks, embeddings, "my_collection");

// 3. Retrieve + answer
String query = "What is the main topic of the document?";
List<Document> relevantDocs = vectorStore.similaritySearch(query, 3);
ChatPromptTemplate qaPrompt = ChatPromptTemplate.fromMessages(List.of(
    Pair.of("system", "Answer based on: ${context}"),
    Pair.of("human", "${question}")
));
FlowInstance qaChain = chainActor.builder()
    .next(qaPrompt)
    .next(ChatAliyun.builder().model("qwen-plus").build())
    .next(new StrOutputParser())
    .build();
String answer = chainActor.invoke(qaChain, Map.of(
    "context", relevantDocs.stream().map(Document::getPageContent).collect(Collectors.joining("\n")),
    "question", query
));
```

**Prerequisites**: Local Ollama + `ollama pull nomic-embed-text`, Milvus service, `rag.vector.milvus.use=true`.

---

## 6. MCP Tool Calling

Connect external tools for LLM to invoke automatically:

```java
// 1. Load MCP config (supports Stdio/SSE/HTTP)
McpClient mcpClient = new McpClient("path/to/mcp-config.json");

// 2. Get tool list
List<ToolDesc> tools = mcpClient.manifest();

// 3. Execute tool directly
ToolResult result = mcpClient.run("weather_tool", Map.of("city", "Beijing"));

// 4. Inject into LLM for auto tool-calling
ChatAliyun llm = ChatAliyun.builder().model("qwen-plus").tools(tools).build();
AIMessage response = llm.invoke("What's the weather in Beijing?");
```

**Config**: MCP config file defines Stdio/SSE/HTTP connection for tools like `weather_tool`.

---

**More**: Dynamic routing, parallel execution, streaming, JSON parsing, event monitoring → [README](../../README.md#-core-capabilities) | [MyFirstAIApp](../../src/test/java/org/salt/jlangchain/demo/flow/MyFirstAIApp.java)
