# J-LangChain 快速开始

5 分钟让你跑起来。详细示例见 [README 核心能力展示](../../README_CN.md#-核心能力展示) 和 [MyFirstAIApp 源码](../../src/test/java/org/salt/jlangchain/demo/flow/MyFirstAIApp.java)。

---

## 1. 添加依赖

```xml
<dependency>
    <groupId>io.github.flower-trees</groupId>
    <artifactId>j-langchain</artifactId>
    <version>1.0.12</version>
</dependency>
```

## 2. 配置

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
export ALIYUN_KEY=sk-xxx...   # 阿里云千问（本教程示例）
```

## 3. 第一个 AI 应用

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

## 4. 运行

```bash
export ALIYUN_KEY=your-key
mvn test -Dtest=MyFirstAIApp#hello
```

---

## 5. RAG 文档问答

构建文档问答助手：加载 PDF → 分块 → 向量嵌入 → 存入 Milvus → 检索 + LLM 回答。

```java
// 1. 加载并分割文档
PdfboxLoader loader = PdfboxLoader.builder().filePath("path/to/document.pdf").build();
List<Document> docs = loader.load();
StanfordNLPTextSplitter splitter = StanfordNLPTextSplitter.builder().chunkSize(500).chunkOverlap(50).build();
List<Document> chunks = splitter.splitDocument(docs);

// 2. 向量存储（需配置 Milvus 和 Ollama）
OllamaEmbeddings embeddings = OllamaEmbeddings.builder().model("nomic-embed-text").vectorSize(768).build();
VectorStore vectorStore = Milvus.fromDocuments(chunks, embeddings, "my_collection");

// 3. 检索 + 回答
String query = "文档的主题是什么？";
List<Document> relevantDocs = vectorStore.similaritySearch(query, 3);
ChatPromptTemplate qaPrompt = ChatPromptTemplate.fromMessages(List.of(
    Pair.of("system", "根据以下内容回答: ${context}"),
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

**前置条件**：本地 Ollama + `ollama pull nomic-embed-text`，Milvus 服务，`rag.vector.milvus.use=true`。

---

## 6. MCP 工具调用

连接外部工具，让 LLM 自动调用：

```java
// 1. 加载 MCP 配置（支持 Stdio/SSE/HTTP）
McpClient mcpClient = new McpClient("path/to/mcp-config.json");

// 2. 获取工具列表
List<ToolDesc> tools = mcpClient.manifest();

// 3. 直接执行工具
ToolResult result = mcpClient.run("weather_tool", Map.of("city", "Beijing"));

// 4. 注入 LLM，自动决策调用
ChatAliyun llm = ChatAliyun.builder().model("qwen-plus").tools(tools).build();
AIMessage response = llm.invoke("北京今天天气怎么样？");
```

**配置**：MCP 配置文件需定义 `weather_tool` 等工具的 Stdio/SSE/HTTP 连接方式。

---

**更多**：动态路由、并行执行、流式输出、JSON 解析、事件监控 → [README](../../README_CN.md#-核心能力展示) | [MyFirstAIApp](../../src/test/java/org/salt/jlangchain/demo/flow/MyFirstAIApp.java)
