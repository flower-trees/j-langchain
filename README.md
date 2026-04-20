<div align="center">

# J-LangChain

**🚀 The LangChain for Java — Build LLM Applications with Spring Boot in Minutes**

[![Maven Central](https://img.shields.io/maven-central/v/io.github.flower-trees/j-langchain.svg)](https://search.maven.org/artifact/io.github.flower-trees/j-langchain)
[![GitHub stars](https://img.shields.io/github/stars/flower-trees/j-langchain?style=social)](https://github.com/flower-trees/j-langchain)
[![GitHub forks](https://img.shields.io/github/forks/flower-trees/j-langchain?style=social)](https://github.com/flower-trees/j-langchain/fork)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](https://opensource.org/licenses/Apache-2.0)
[![Java](https://img.shields.io/badge/Java-17+-green.svg)](https://www.oracle.com/java/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.2+-green.svg)](https://spring.io/projects/spring-boot)

[English](#) | [中文文档](./README_CN.md)

**Stop rewriting LLM boilerplate.** J-LangChain gives Spring Boot developers chains, agents, RAG, MCP, and 13+ LLM vendors — all in one dependency.

⭐ **If this saves you time, please give it a Star — it really helps!** ⭐

</div>

---

## 🆚 Why J-LangChain over LangChain4j / Spring AI?

| Feature | J-LangChain | LangChain4j | Spring AI |
|---------|-------------|-------------|-----------|
| **Multi-Vendor LLM** | ✅ 13+ mainstream (OpenAI / DeepSeek / Qwen / Kimi…) | ✅ 5+ | ⚠️ Limited |
| **Flow Orchestration** | ✅ Sequential / Parallel / Nested / Conditional | ⚠️ Chained calls | ⚠️ Basic |
| **RAG** | ✅ End-to-end (Load → Split → Embed → Vector) | ✅ Complete | ⚠️ Partial |
| **MCP Protocol** | ✅ 3 modes + Function Calling + Agents | ❌ | ❌ |
| **AgentExecutor** | ✅ One-line ReAct agent with `@AgentTool` | ⚠️ Manual | ⚠️ Basic |
| **TTS Support** | ✅ Smart stream + auto-filter | ❌ | ❌ |
| **Event Monitoring** | ✅ Full lifecycle | ⚠️ Basic | ❌ |
| **Spring Boot** | ✅ Native auto-config | ✅ | ✅ |

---

## 🚀 Quick Start (5 Minutes)

### 1. Add Dependency

```xml
<dependency>
    <groupId>io.github.flower-trees</groupId>
    <artifactId>j-langchain</artifactId>
    <version>1.0.14</version>
</dependency>
```

### 2. Import Config

```java
@SpringBootApplication
@Import(JLangchainConfig.class)
public class Application {
    public static void main(String[] args) {
        SpringApplication.run(Application.class, args);
    }
}
```

### 3. Set API Key & Run

```bash
export ALIYUN_KEY=sk-xxx...   # or CHATGPT_KEY / DEEPSEEK_KEY / OLLAMA_KEY1 ...
```

> All 18 environment variables and their corresponding model classes: [API Reference → Environment Variables](./docs/api/reference.md#12-environment-variables)

```java
@Autowired ChainActor chainActor;

FlowInstance chain = chainActor.builder()
    .next(PromptTemplate.fromTemplate("Tell me a joke about ${topic}"))
    .next(ChatAliyun.builder().model("qwen-plus").build())
    .next(new StrOutputParser())
    .build();

String result = chainActor.invoke(chain, Map.of("topic", "programmers"));
// → Why do programmers prefer dark mode? Because light attracts bugs! 🐛
```

✅ Prompt → LLM → Parser. That's it.

---

## 💡 What Can You Build?

### ⚡ Streaming Output — ChatGPT-style typewriter in 3 lines

```java
ChatOllama llm = ChatOllama.builder().model("llama3:8b").build();
AIMessageChunk chunk = llm.stream("Explain quantum computing simply");
chunk.getIterator().forEachRemaining(c -> System.out.print(c.getContent()));
// Quantum| computing| uses| qubits|...
```

### 🤖 ReAct Agent — annotate a method, get an agent

```java
@AgentTool(description = "Search flight prices between two cities")
public String searchFlights(
    @Param("departure city") String from,
    @Param("destination city") String to) {
    return from + "→" + to + ": Economy $299 / Business $899";
}

AgentExecutor agent = AgentExecutor.builder()
    .llm(ChatAliyun.builder().model("qwen-plus").build())
    .toolScanner(new ToolScanner(this))
    .build();

agent.invoke("Find the cheapest flight from Beijing to Shanghai");
// Agent thinks → calls searchFlights → reasons → gives answer ✅
```

### 🔀 Parallel Agents — fan-out research, fan-in summary

```java
// Three specialized agents run concurrently, results merged automatically
FlowInstance researchChain = chainActor.builder()
    .concurrent(
        cAlias("flights",  flightAgent),
        cAlias("hotels",   hotelAgent),
        cAlias("weather",  weatherAgent)
    )
    .next(summaryAgent)   // merges all three results
    .build();

// Article 18: Article18ParallelTravelResearch.java
```

### 📚 RAG — PDF to Q&A in 50 lines

```java
PdfboxLoader loader = new PdfboxLoader("report.pdf");
List<Document> chunks = StanfordNLPTextSplitter.builder()
    .chunkSize(500).chunkOverlap(50).build()
    .splitDocument(loader.load());

Milvus vectorStore = new Milvus(new OllamaEmbeddings());
vectorStore.addDocuments(chunks);

// Retrieve + answer
List<Document> relevant = vectorStore.similaritySearch("What is the conclusion?", 3);
String answer = chainActor.invoke(qaChain, Map.of("context", relevant, "question", "..."));
```

### 🔧 RPC Zero-Intrusion — expose Dubbo/Feign as AI tools with 2 annotations

```java
// No changes to your existing RPC interface
@AgentTool(description = "Query order status")
public OrderVO queryOrder(@Param("order ID") String orderId) {
    return orderService.query(orderId);  // existing RPC call unchanged
}
// @ParamDesc on VO fields → auto schema generation
// Article 19: Article19RpcMcpTools.java
```

### 🎙️ LLM → TTS — make your AI speak

```java
AIMessageChunk llmStream = llm.stream("Tell me about the weather today");
TtsCardChunk audio = aliyunTts.stream(llmStream);
// Streams audio chunks in real time; brackets auto-filtered before synthesis
```

---

## 📖 19 Tutorials: From Hello World to Multi-Agent Systems

Every tutorial has a runnable demo class — no setup beyond a single API key.

| Tutorial | What You Build | Level |
|----------|----------------|-------|
| [01 · First AI App](./docs/article/001-en/01-hello-ai.md) | Prompt→LLM→Parser, streaming, JSON output | ⭐ |
| [02 · Chain Patterns](./docs/article/001-en/02-chain-patterns.md) | Sequential / Parallel / Conditional / Nested chains | ⭐⭐ |
| [06 · Streaming](./docs/article/001-en/06-streaming.md) | `stream` / `streamEvent` / stop, SSE push | ⭐⭐ |
| [07 · Multi-Model](./docs/article/001-en/07-multi-model.md) | Unified API across 13+ LLMs, hot-swap | ⭐ |
| [03 · RAG Pipeline](./docs/article/001-en/03-rag-pipeline.md) | PDF → split → embed → Milvus → Q&A | ⭐⭐⭐ |
| [04 · ReAct Agent](./docs/article/001-en/04-react-agent.md) | Tool definition, ReAct reasoning loop from scratch | ⭐⭐⭐ |
| [09 · AgentExecutor](./docs/article/001-en/09-agent-executor.md) | One-line ReAct agent, `@AgentTool` annotation | ⭐⭐ |
| [10 · Flight Price Agent](./docs/article/001-en/10-flight-compare-agent.md) | Multi-step tool-calling: compare & book flights | ⭐⭐ |
| [05 · LLM + TTS](./docs/article/001-en/05-llm-tts.md) | LLM → speech, smart stream synthesis | ⭐⭐ |
| [08 · MCP Basics](./docs/article/001-en/08-mcp.md) | MCP protocol, McpManager / McpClient | ⭐⭐⭐ |
| [11–14 · MCP Agents](./docs/article/001-en/11-mcp-react-agent.md) | Function Calling ReAct, HTTP/NPX/mixed MCP | ⭐⭐⭐ |
| [15 · Travel Planner](./docs/article/001-en/15-agent-executor-embed.md) | Nested AgentExecutor inside a chain | ⭐⭐ |
| [16 · Dual-Agent Service](./docs/article/001-en/16-multi-agent-executor.md) | ReAct + MCP serial pipeline, file output | ⭐⭐⭐ |
| [18 · Parallel Agents](./docs/article/001-en/18-parallel-agent-concurrent.md) | 3 concurrent agents, fan-out/fan-in | ⭐⭐⭐ |
| [19 · RPC Zero-Intrusion](./docs/article/001-en/19-rpc-vo-param.md) | Dubbo/Feign → AI tool, 2 annotations | ⭐⭐ |

➡️ [Full tutorial index with reading order →](./docs/article/001-en/README.md)

---

## ✨ Core Features at a Glance

### 🎯 13+ LLM Integrations — one unified API
OpenAI · Ollama · DeepSeek · Alibaba Qwen · Moonshot (Kimi) · Doubao · Coze · Hunyuan · Qianfan (ERNIE) · Zhipu GLM · MiniMax · Lingyi Yi · Stepfun

### 🔗 Chain Orchestration
Sequential · Parallel · Nested · Conditional routing · Streaming output · Full event lifecycle

### 📚 RAG
PDF / Word / OCR loaders · Smart text splitting · OpenAI / Ollama / Alibaba embeddings · Milvus vector store

### 🤖 Agent & MCP
`AgentExecutor` (ReAct) · `McpAgentExecutor` (Function Calling) · `@AgentTool` / `ToolScanner` · MCP Stdio / SSE / HTTP · Multi-app history with thread safety

### 🎤 TTS
Alibaba Cloud · Doubao · Smart sentence splitting · Bracket auto-filter · Real-time audio streaming

---

## 🏗️ Architecture

```
┌─────────────────────────────────────────────────────────┐
│              Your Spring Boot Application               │
└────────────────────────┬────────────────────────────────┘
                         ▼
┌─────────────────────────────────────────────────────────┐
│                    J-LangChain Core                     │
│   ChainActor · PromptTemplate · OutputParser · History  │
└──────────┬───────────┬───────────┬──────────┬──────────┘
           ▼           ▼           ▼          ▼
      LLM (13+)      RAG         TTS         MCP
   Multi-vendor   Load/Split  Smart stream  Stdio/SSE
   chat + tools  Embed/Vector  Auto-filter   HTTP/FC
```

---

## 📄 Documentation

| | |
|--|--|
| [Quick Start](./docs/guide/quickstart.md) | Detailed setup guide |
| [Tutorial Series (EN)](./docs/article/001-en/README.md) | 19 articles, reading order, runnable demos |
| [Tutorial Series (CN)](./docs/article/001/README.md) | 中文系列教程 |
| [Sample Code](./src/test/java/org/salt/jlangchain/demo/) | 30+ runnable examples |
| [API Reference](./docs/api/reference.md) | Full API docs |

---

## 🤝 Contributing

- 🐛 [Report a Bug](https://github.com/flower-trees/j-langchain/issues)
- 💡 [Suggest a Feature](https://github.com/flower-trees/j-langchain/issues)
- 🔧 [Submit a Pull Request](https://github.com/flower-trees/j-langchain/pulls)

```bash
git checkout -b feature/your-feature
git commit -m 'Add your feature'
git push origin feature/your-feature
# Then open a Pull Request
```

---

## 📄 License

[Apache License 2.0](./LICENSE)

## 🙏 Acknowledgments

[LangChain](https://github.com/langchain-ai/langchain) · [salt-function-flow](https://github.com/flower-trees/salt-function-flow) · [Spring Boot](https://spring.io/projects/spring-boot)

---

<div align="center">

**⭐ Found this useful? A Star takes 2 seconds and helps the project grow — thank you! ⭐**

[Quick Start](#-quick-start-5-minutes) · [19 Tutorials](#-19-tutorials-from-hello-world-to-multi-agent-systems) · [Sample Code](./src/test/java/org/salt/jlangchain/demo/) · [Issues](https://github.com/flower-trees/j-langchain/issues)

</div>
