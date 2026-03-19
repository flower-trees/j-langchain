<div align="center">

# J-LangChain

**🚀 Enterprise-Grade LLM Application Development Framework for the Java Platform**

[![Maven Central](https://img.shields.io/maven-central/v/io.github.flower-trees/j-langchain.svg)](https://search.maven.org/artifact/io.github.flower-trees/j-langchain)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](https://opensource.org/licenses/Apache-2.0)
[![Java](https://img.shields.io/badge/Java-17+-green.svg)](https://www.oracle.com/java/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.2+-green.svg)](https://spring.io/projects/spring-boot)

[English](#) | [中文文档](./README_CN.md)

A powerful, flexible, and easy-to-use Java LangChain implementation — building AI applications is as simple as stacking blocks

</div>

---

## ✨ Core Features

### 🎯 Multi-Vendor LLM Support
Out-of-the-box integrations with mainstream large language models, unified API for all:

- **OpenAI (ChatGPT)** - GPT-4/GPT-3.5 series
- **Ollama** - Local open-source models (Llama3, Qwen2.5, etc.)
- **Alibaba Cloud Qwen** - Enterprise cloud service
- **Moonshot (Kimi)** - Long-context support
- **Doubao** - ByteDance LLM
- **Coze** - OAuth 2.0 + SSE real-time streaming

### 🔗 Flexible Chain Orchestration
Built on top of [salt-function-flow](https://github.com/flower-trees/salt-function-flow) powerful workflow orchestration:

- **Sequential/Parallel/Nested** - Combine multiple execution modes
- **Conditional Routing** - Dynamically select execution paths
- **Streaming Output** - Real-time token delivery
- **Event Monitoring** - Full lifecycle tracking

### 📚 Complete RAG Support
End-to-end Retrieval-Augmented Generation:

- **Document Loaders** - PDF, Word (DOC/DOCX) + OCR recognition
- **Text Splitting** - Smart chunking + overlap handling
- **Vector Embeddings** - OpenAI/Ollama/Alibaba Cloud support
- **Vector Database** - Milvus integration + similarity search

### 🔧 Tool Calling & MCP Protocol
- **Tool Calling** - Function calls + parameter schema
- **MCP (Model Context Protocol)** - 3 connection modes (Stdio/SSE/HTTP)
- **Environment Variable Config** - Dynamic gateway routing

### 🎤 Intelligent Speech Synthesis (TTS)
- **Multi-Vendor Support** - Alibaba Cloud/Doubao
- **Bracket Content Filtering** - Automatic TTS text optimization
- **Smart Sentence Splitting** - Stream generation based on punctuation
- **Streaming Audio** - Real-time chunk delivery

---

## 🚀 5-Minute Quick Start

### 1️⃣ Add Dependency

**Maven:**
```xml
<dependency>
    <groupId>io.github.flower-trees</groupId>
    <artifactId>j-langchain</artifactId>
    <version>1.0.11</version>
</dependency>
```

**Gradle:**
```groovy
implementation 'io.github.flower-trees:j-langchain:1.0.11'
```

### 2️⃣ Configure Application Class

```java
@SpringBootApplication
@Import(JLangchainConfig.class)  // Import J-LangChain config
public class Application {
    public static void main(String[] args) {
        SpringApplication.run(Application.class, args);
    }
}
```

### 3️⃣ Set API Key

```bash
# LLM Chat Models
export CHATGPT_KEY=sk-xxx...      # OpenAI (ChatGPT) API Key
export ALIYUN_KEY=sk-xxx...      # Alibaba Cloud Qwen API Key
export MOONSHOT_KEY=sk-xxx...    # Moonshot (Kimi) API Key
export DOUBAO_KEY=sk-xxx...      # Doubao API Key
export COZE_KEY=xxx...           # Coze API Key (or use OAuth below)
export OLLAMA_KEY1=              # Ollama: usually empty for local; set for gateway/proxy

# Coze OAuth 2.0 (alternative)
# export COZE_CLIENT_ID=xxx
# export COZE_PRIVATE_KEY_PATH=/path/to/private-key.pem
# export COZE_PUBLIC_KEY_ID=xxx

# TTS Speech Synthesis
export ALIYUN_TTS_KEY=xxx...     # Alibaba Cloud TTS
export DOUBAO_TTS_KEY=xxx...     # Doubao TTS

# Or configure in application.yml via models.xxx.chat-key, tts.xxx.api-key
```

### 4️⃣ Build Your First AI Application

```java
@Component
public class MyFirstAIApp {

    @Autowired
    private ChainActor chainActor;

    public void hello() {
        // 1. Create Prompt template
        PromptTemplate prompt = PromptTemplate.fromTemplate(
            "Tell me a joke about ${topic}"
        );

        // 2. Select LLM (Alibaba Cloud Qwen)
        ChatAliyun llm = ChatAliyun.builder()
            .model("qwen-plus")
            .build();

        // 3. Build chain
        FlowInstance chain = chainActor.builder()
            .next(prompt)
            .next(llm)
            .next(new StrOutputParser())
            .build();

        // 4. Execute and get result
        ChatGeneration result = chainActor.invoke(
            chain,
            Map.of("topic", "programmers")
        );

        System.out.println(result.getText());
        // Output: Why do programmers prefer dark mode?
        //         Because light attracts bugs! 🐛
    }
}
```

✅ **That simple!** 3 core lines of code for a complete AI chat application.

---

## 💡 Core Capabilities Demo

### 🔀 Dynamic Routing - Switch Models Based on Input

```java
ChatOllama ollamaModel = ChatOllama.builder().model("qwen2.5:0.5b").build();
ChatAliyun qwenModel = ChatAliyun.builder().model("qwen-plus").build();

FlowInstance chain = chainActor.builder()
    .next(prompt)
    .next(
        Info.c("vendor == 'ollama'", ollamaModel),
        Info.c("vendor == 'aliyun'", qwenModel),
        Info.c(input -> "Unsupported vendor")
    )
    .next(new StrOutputParser())
    .build();

// Dynamically select model: ollama=local, aliyun=Alibaba Cloud Qwen
chainActor.invoke(chain, Map.of("topic", "AI", "vendor", "aliyun"));
```

### ⚡ Parallel Execution - Generate Jokes and Poems at Once

```java
FlowInstance jokeChain = chainActor.builder()
    .next(jokePrompt).next(llm).build();

FlowInstance poemChain = chainActor.builder()
    .next(poemPrompt).next(llm).build();

FlowInstance parallelChain = chainActor.builder()
    .concurrent(
        (ctx, timeout) -> Map.of(
            "joke", ctx.getResult(jokeChain.getFlowId()),
            "poem", ctx.getResult(poemChain.getFlowId())
        ),
        jokeChain,
        poemChain
    )
    .build();

Map<String, String> result = chainActor.invoke(
    parallelChain,
    Map.of("topic", "cats")
);
// Returns both joke and poem 🎭
```

### 🌊 Streaming Output - ChatGPT-Style Typewriter Effect

```java
ChatOllama llm = ChatOllama.builder().model("llama3:8b").build();

AIMessageChunk chunk = llm.stream("What is the meaning of life?");
while (chunk.getIterator().hasNext()) {
    String token = chunk.getIterator().next().getContent();
    System.out.print(token); // Output character by character
}
```

**Output:**
```
The| meaning| of| life| is| to| find| your| purpose|...
```

### 📊 JSON Structured Output

```java
FlowInstance chain = chainActor.builder()
    .next(llm)
    .next(new JsonOutputParser())  // Auto-parse JSON
    .build();

List<Map> countries = chainActor.stream(chain,
    "List 3 countries with populations in JSON format");
// Returns: [{"country":"China","population":1439323776}, ...]
```

### 🔍 Event Stream Monitoring - Debugging Essential

```java
EventMessageChunk events = chainActor.streamEvent(chain, input);
while (events.getIterator().hasNext()) {
    EventMessageChunk event = events.getIterator().next();
    System.out.println(event.toJson());
}
```

**Output (full execution trace):**
```json
{"event":"on_chain_start","data":{"input":{"topic":"AI"}},"name":"ChainActor"}
{"event":"on_prompt_start","data":{"input":{"topic":"AI"}},"name":"PromptTemplate"}
{"event":"on_llm_start","data":{"input":"Tell me about AI"},"name":"ChatOpenAI"}
{"event":"on_llm_stream","data":{"chunk":"Artificial"},"name":"ChatOpenAI"}
{"event":"on_llm_end","data":{"output":"..."},"name":"ChatOpenAI"}
```

### 🎙️ TTS Speech Synthesis (with Smart Filtering)

```java
AliyunTts tts = AliyunTts.builder()
    .appKey("your-app-key")
    .voice("xiaoyun")
    .format("wav")
    .build();

TtsCardChunk audioChunk = tts.stream("Hello (你好), welcome to J-LangChain!");
// Auto-filters bracket content, synthesizes only: "Hello, welcome to J-LangChain!"
```

---

## 📚 Complete RAG Example

Build a "Document Q&A Assistant":

```java
// 1. Load PDF document
PdfboxLoader loader = new PdfboxLoader("path/to/document.pdf");
List<Document> docs = loader.load();

// 2. Text splitting
TextSplitter splitter = StanfordNLPTextSplitter.builder().chunkSize(500).chunkOverlap(50).build();
List<Document> chunks = splitter.splitDocument(docs);

// 3. Vector embedding
OllamaEmbeddings embeddings = new OllamaEmbeddings();
List<List<Float>> vectors = embeddings.embedDocuments(chunks);

// 4. Store in vector database
Milvus vectorStore = new Milvus(embeddings);
vectorStore.addDocuments(chunks);

// 5. Retrieve + Answer
String query = "What is the main topic of the document?";
List<Document> relevantDocs = vectorStore.similaritySearch(query, 3);

ChatPromptTemplate qaPrompt = ChatPromptTemplate.fromMessages(List.of(
    Pair.of("system", "Answer based on: ${context}"),
    Pair.of("human", "${question}")
));

FlowInstance qaChain = chainActor.builder()
    .next(qaPrompt)
    .next(ChatOpenAI.builder().build())
    .next(new StrOutputParser())
    .build();

String answer = chainActor.invoke(qaChain, Map.of(
    "context", relevantDocs.stream()
        .map(Document::getPageContent)
        .collect(Collectors.joining("\n")),
    "question", query
));
```

---

## 🛠️ MCP Tool Calling

Connect external tools to extend LLM capabilities:

```java
// 1. Configure MCP tools (supports Stdio/SSE/HTTP)
McpClient mcpClient = new McpClient("path/to/mcp-config.json");

// 2. Get available tools
List<ToolDesc> tools = mcpClient.manifest();

// 3. Execute tool
ToolResult result = mcpClient.run("weather_tool", Map.of(
    "city", "Beijing"
));
System.out.println(result.getContent());
// Output: "Beijing: 25°C, Sunny"

// 4. Use with LLM
ChatOpenAI llm = ChatOpenAI.builder()
    .tools(tools)  // Inject tool definitions
    .build();

AIMessage response = llm.invoke("What's the weather in Beijing?");
// LLM automatically calls weather_tool and returns result
```

---

## 📖 Full Documentation

| Document | Description |
|----------|-------------|
| [Quick Start](./docs/guide/quickstart.md) | Detailed getting started guide |
| [API Reference](./docs/api/reference.md) | Complete API reference |
| [MyFirstAIApp](./src/test/java/org/salt/jlangchain/demo/flow/MyFirstAIApp.java) / [Sample Code](./src/test/java/org/salt/jlangchain/demo/) | Quick start + 30+ real-world examples |
| [Best Practices](./docs/best-practices.md) | Production recommendations |

---

## 🏗️ Architecture

```
┌─────────────────────────────────────────────────────────┐
│                    Application Layer                    │
│          (Your AI Application / Spring Boot)            │
└─────────────────────────────────────────────────────────┘
                           ▼
┌─────────────────────────────────────────────────────────┐
│                   J-LangChain Core                      │
│  ┌─────────────┬──────────────┬──────────────────────┐ │
│  │ ChainActor  │ PromptTpl    │ OutputParser         │ │
│  │ (Orchestr.) │ (Templates)  │ (Str/JSON/Function)  │ │
│  └─────────────┴──────────────┴──────────────────────┘ │
└─────────────────────────────────────────────────────────┘
                           ▼
┌───────────────┬──────────────┬─────────────┬───────────┐
│  LLM Vendors  │     RAG      │     TTS     │    MCP    │
│ ChatGPT/Ollama│ Loader/Split │ Aliyun/Doubao│ Tools     │
│ Aliyun/Moonshot│ Embed/Vector│ Smart Filter│ 3 Protocols│
│ Doubao/Coze   │ Milvus Store │ Streaming   │ OAuth     │
└───────────────┴──────────────┴─────────────┴───────────┘
```

---

## 🌟 Why J-LangChain?

### 🆚 Comparison with Alternatives

| Feature | J-LangChain | LangChain4j | Spring AI |
|---------|-------------|-------------|-----------|
| **Multi-Vendor** | ✅ 6+ (incl. domestic) | ✅ 5+ | ⚠️ Mainstream |
| **Flow Orchestration** | ✅ Powerful salt-flow | ⚠️ Chained calls | ⚠️ Basic |
| **RAG Completeness** | ✅ End-to-end | ✅ Complete | ⚠️ Partial |
| **TTS Support** | ✅ Smart optimization | ❌ | ❌ |
| **MCP Protocol** | ✅ 3 connection modes | ❌ | ❌ |
| **Event Monitoring** | ✅ Full lifecycle | ⚠️ Basic | ❌ |
| **Spring Boot** | ✅ Native support | ✅ | ✅ |
| **Learning Curve** | 🟢 Easy | 🟡 Medium | 🟢 Easy |

### 💪 Core Advantages

1. **🇨🇳 Domestic LLM First** - Deep integration with Alibaba Cloud, Doubao, Kimi, etc.
2. **🔄 Powerful Orchestration** - Workflow engine based on salt-function-flow
3. **📦 Out of the Box** - Spring Boot auto-configuration, zero-config startup
4. **🎯 Production-Ready** - Event tracking, error handling, performance tuning
5. **🔧 Highly Extensible** - Clear abstraction layers, easy to add new vendors

---

## 🤝 Contributing

We welcome contributions of all kinds!

- 🐛 [Report a Bug](https://github.com/flower-trees/j-langchain/issues)
- 💡 [Suggest a Feature](https://github.com/flower-trees/j-langchain/issues)
- 📝 Improve documentation
- 🔧 Submit Pull Request

### How to Contribute
```bash
# 1. Fork the project
# 2. Create a feature branch
git checkout -b feature/amazing-feature

# 3. Commit changes
git commit -m 'Add amazing feature'

# 4. Push branch
git push origin feature/amazing-feature

# 5. Create Pull Request
```

---

## 📄 License

This project is licensed under the [Apache License 2.0](./LICENSE).

---

## 🙏 Acknowledgments

- [LangChain](https://github.com/langchain-ai/langchain) - Inspiration
- [salt-function-flow](https://github.com/flower-trees/salt-function-flow) - Flow orchestration engine
- [Spring Boot](https://spring.io/projects/spring-boot) - Application framework

---

## 📞 Contact

- 📧 Email: huaqianshu_z@163.com
- 🐛 Issues: [GitHub Issues](https://github.com/flower-trees/j-langchain/issues)
- 💬 Discussions: [GitHub Discussions](https://github.com/flower-trees/j-langchain/discussions)

---

<div align="center">

**⭐ If this project helps you, please give us a Star!**

Made with ❤️ by J-LangChain Team

[Quick Start](#-5-minute-quick-start) • [MyFirstAIApp](./src/test/java/org/salt/jlangchain/demo/flow/MyFirstAIApp.java) • [Sample Code](./src/test/java/org/salt/jlangchain/demo/) • [API Reference](#)

</div>
