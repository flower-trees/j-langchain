# J-LangChain API Reference

All packages use the prefix `org.salt.jlangchain`.

---

## 1. ChainActor

**Package**: `org.salt.jlangchain.core.ChainActor`

Orchestrates Prompt, LLM, and Parser nodes into executable chains.

| Method | Description | Returns |
|--------|-------------|---------|
| `builder()` | Get chain builder | `FlowEngine.Builder` |
| `invoke(flow, input)` | Execute synchronously | Output type `O` |
| `invoke(flow, input, transmitMap)` | Execute with pass-through params | Output type `O` |
| `stream(flow, input)` | Streaming execution | `ChatGenerationChunk` |
| `streamEvent(flow, input)` | Event stream execution | `EventMessageChunk` |
| `streamEvent(flow, input, filter)` | Event stream with filter | `EventMessageChunk` |
| `stop(flowInstance)` | Stop chain | void |

```java
FlowInstance chain = chainActor.builder()
    .next(prompt)
    .next(llm)
    .next(new StrOutputParser())
    .build();

String result = chainActor.invoke(chain, Map.of("topic", "AI"));
```

---

## 2. LLM Models

**Base class**: `org.salt.jlangchain.core.llm.BaseChatModel`

All implementations support `invoke`, `stream`, `streamEvent`, and `withConfig`.

**Supported input types**: `String`, `StringPromptValue`, `ChatPromptValue`

### 2.1 All Supported Models

| Class | Package | Env Variable | Description |
|-------|---------|--------------|-------------|
| `ChatOpenAI` | `core.llm.openai` | `CHATGPT_KEY` | OpenAI GPT-4 / GPT-3.5 |
| `ChatOllama` | `core.llm.ollama` | `OLLAMA_KEY1` | Local open-source models |
| `ChatAliyun` | `core.llm.aliyun` | `ALIYUN_KEY` | Alibaba Cloud Qwen |
| `ChatMoonshot` | `core.llm.moonshot` | `MOONSHOT_KEY` | Moonshot (Kimi) |
| `ChatDoubao` | `core.llm.doubao` | `DOUBAO_KEY` | Doubao (ByteDance) |
| `ChatCoze` | `core.llm.doubao` | `COZE_KEY` | Coze |
| `ChatDeepseek` | `core.llm.deepseek` | `DEEPSEEK_KEY` | DeepSeek-V3 / R1 |
| `ChatHunyuan` | `core.llm.hunyuan` | `HUNYUAN_KEY` | Tencent Hunyuan |
| `ChatQianfan` | `core.llm.qianfan` | `QIANFAN_KEY` | Baidu ERNIE |
| `ChatZhipu` | `core.llm.zhipu` | `ZHIPU_KEY` | Zhipu GLM |
| `ChatMinimax` | `core.llm.minimax` | `MINIMAX_KEY` | MiniMax |
| `ChatLingyi` | `core.llm.lingyi` | `LINGYI_KEY` | 01.AI Yi |
| `ChatStepfun` | `core.llm.stepfun` | `STEPFUN_KEY` | StepFun |

### 2.2 Common Builder Parameters

| Param | Type | Default | Description |
|-------|------|---------|-------------|
| `model` | String | vendor default | Model name (e.g. `qwen-plus`, `gpt-4`) |
| `temperature` | Float | 0.7 | Sampling temperature |
| `tools` | `List<AiChatInput.Tool>` | — | Tool calling definitions |

```java
ChatAliyun llm = ChatAliyun.builder()
    .model("qwen-plus")
    .temperature(0.7f)
    .build();

AIMessage response = llm.invoke("Hello");
AIMessageChunk stream = llm.stream("Tell me a story");
```

### 2.3 Coze OAuth 2.0

```java
// OAuth alternative to COZE_KEY
export COZE_CLIENT_ID=xxx
export COZE_PRIVATE_KEY_PATH=/path/to/private-key.pem
export COZE_PUBLIC_KEY_ID=xxx
```

---

## 3. Prompt Templates

### 3.1 PromptTemplate

**Package**: `org.salt.jlangchain.core.prompt.string.PromptTemplate`

Simple string template with `${variable}` placeholders.

```java
PromptTemplate prompt = PromptTemplate.fromTemplate("Tell me about ${topic}");
// Input: Map.of("topic","AI") → Output: "Tell me about AI"
```

### 3.2 ChatPromptTemplate

**Package**: `org.salt.jlangchain.core.prompt.chat.ChatPromptTemplate`

Multi-turn dialogue template supporting `system` / `human` / `ai` roles.

```java
ChatPromptTemplate prompt = ChatPromptTemplate.fromMessages(List.of(
    Pair.of("system", "You are a helpful assistant. Context: ${context}"),
    Pair.of("human", "${question}")
));
```

---

## 4. Output Parsers

| Class | Package | Description |
|-------|---------|-------------|
| `StrOutputParser` | `core.parser` | `AIMessage` → `String` |
| `JsonOutputParser` | `core.parser` | LLM output → parsed JSON |
| `FunctionOutputParser` | `core.parser` | Custom function parser for stream chunks |

---

## 5. Agent & Tool Calling

### 5.1 AgentExecutor

**Package**: `org.salt.jlangchain.core.agent.AgentExecutor`

Wraps the ReAct (Reason + Act) loop. Automatically calls tools and reasons until a final answer.

| Builder Param | Type | Description |
|---------------|------|-------------|
| `llm` | `BaseChatModel` | LLM to reason with |
| `toolScanner` | `ToolScanner` | Scans `@AgentTool` methods |
| `maxIterations` | int | Max ReAct cycles (default: 10) |
| `onThought` | `Consumer<String>` | Callback on each thought step |
| `onObservation` | `Consumer<String>` | Callback on tool result |
| `onLlm` | `Consumer<String>` | Callback before each LLM call (new in 1.0.14) |

```java
AgentExecutor agent = AgentExecutor.builder()
    .llm(ChatAliyun.builder().model("qwen-plus").build())
    .toolScanner(new ToolScanner(this))
    .build();

String result = agent.invoke("Find flights from Beijing to Shanghai");
```

### 5.2 McpAgentExecutor

**Package**: `org.salt.jlangchain.core.agent.McpAgentExecutor`

Model-side Function Calling with MCP tool orchestration. Uses native tool-call messages instead of ReAct prompting.

```java
McpAgentExecutor agent = McpAgentExecutor.builder()
    .llm(ChatAliyun.builder().model("qwen-plus").build())
    .mcpManager(mcpManager)
    .build();
```

### 5.3 @AgentTool / @Param / @ParamDesc / ToolScanner

**Package**: `org.salt.jlangchain.rag.tools.annotation`

Declare tools by annotating Java methods:

```java
@AgentTool(description = "Search flight prices between two cities")
public String searchFlights(
    @Param("departure city") String from,
    @Param("destination city") String to) {
    return from + "→" + to + ": $299 Economy";
}
```

**`@AgentTool`** — marks a method as an AI tool.

| Attribute | Description |
|-----------|-------------|
| `description` | Tool description shown to the LLM |
| `params` | `@ParamDesc[]` array for third-party VO parameter descriptions (1.0.14+) |

**`@Param`** — annotates a method parameter or a VO field (1.0.14+, supports `ElementType.PARAMETER` and `ElementType.FIELD`).

**`@ParamDesc`** — annotates VO fields on third-party objects where direct `@Param` is not possible. Priority: `@AgentTool.params` > VO field `@Param` > method parameter `@Param`.

```java
// Zero-intrusion RPC: describe VO fields without modifying the class
@AgentTool(
    description = "Query order",
    params = {
        @ParamDesc(name = "orderId", description = "order ID"),
        @ParamDesc(name = "userId",  description = "user ID")
    }
)
public OrderVO queryOrder(OrderQuery query) { ... }
```

**`ToolScanner`** — scans a bean for `@AgentTool` methods, auto-generates JSON Schema for parameters (including complex object types in 1.0.14+).

```java
ToolScanner scanner = new ToolScanner(myToolBean);
// Pass to AgentExecutor or use standalone to get ToolDesc list
```

---

## 6. MCP Protocol

### 6.1 McpClient

**Package**: `org.salt.jlangchain.rag.tools.mcp.McpClient`

| Constructor | Description |
|-------------|-------------|
| `McpClient()` | Load `mcp.server.config.json` from classpath |
| `McpClient(String configPath)` | Specify config file path |

| Method | Description | Returns |
|--------|-------------|---------|
| `listAllTools()` | All tools from all servers | `Map<String, List<ToolDesc>>` |
| `callTool(serverName, toolName, arguments)` | Call a specific tool | `ToolResult` |
| `getServerStatuses()` | Connection status per server | `Map<String, ServerStatus>` |
| `destroy()` | Close all connections | void |

Config file supports env placeholders: `${VAR_NAME}` or `${VAR_NAME:default}`.

### 6.2 McpManager

**Package**: `org.salt.jlangchain.rag.tools.mcp.McpManager`

Wrapper over `McpClient` with `manifest()` and `manifestForInput()` for LLM tool injection.

```java
List<AiChatInput.Tool> tools = mcpManager.manifest();        // all tools
List<AiChatInput.Tool> tools = mcpManager.manifestForInput(); // tools as LLM input format
```

---

## 7. History Management

**Package**: `org.salt.jlangchain.core.history`

Multi-application conversation history with thread safety (1.0.14+).

### HistoryBase

| Field | Description |
|-------|-------------|
| `appId` | Application identifier (multi-app isolation) |
| `userId` | User identifier |
| `sessionId` | Session identifier |

### MemoryHistory

In-memory history store with three-level `ConcurrentHashMap` (`appId → userId → sessionId`).

| Builder Param | Description |
|---------------|-------------|
| `appId` | Application ID |
| `maxSize` | Max history entries per session (default: unlimited) |

```java
MemoryHistory history = MemoryHistory.builder()
    .appId("my-app")
    .maxSize(20)
    .build();
```

### MemoryHistoryReader / MemoryHistoryStorer

Thread-safe reader and storer. Pair them with an LLM to add conversation memory:

```java
chainActor.builder()
    .next(new MemoryHistoryReader(history))
    .next(prompt)
    .next(llm)
    .next(new MemoryHistoryStorer(history))
    .build();
```

---

## 8. RAG

### 8.1 Document Loaders

| Class | Package | Description |
|-------|---------|-------------|
| `PdfboxLoader` | `rag.loader.pdf` | PDF loading |
| `ApachePoiDocxLoader` | `rag.loader.docx` | Word DOCX |
| `ApachePoiDocLoader` | `rag.loader.doc` | Word DOC (legacy) |

```java
PdfboxLoader loader = PdfboxLoader.builder().filePath("doc.pdf").build();
List<Document> docs = loader.load();
```

### 8.2 Text Splitting

```java
StanfordNLPTextSplitter splitter = StanfordNLPTextSplitter.builder()
    .chunkSize(500)
    .chunkOverlap(50)
    .build();
List<Document> chunks = splitter.splitDocument(docs);
```

### 8.3 Embeddings

| Class | Env Variable | Description |
|-------|--------------|-------------|
| `OllamaEmbeddings` | `OLLAMA_KEY1` | Ollama local |
| `OpenAIEmbeddings` | `CHATGPT_KEY` | OpenAI |
| `AliyunEmbeddings` | `ALIYUN_KEY` | Alibaba Cloud |

```java
OllamaEmbeddings embeddings = OllamaEmbeddings.builder()
    .model("nomic-embed-text")
    .vectorSize(768)
    .build();
```

### 8.4 Vector Store (Milvus)

```java
Milvus vectorStore = Milvus.fromDocuments(docs, embeddings, "collection");
List<Document> results = vectorStore.similaritySearch("query", 3);
```

| Method | Description |
|--------|-------------|
| `addDocuments(docs)` | Add documents |
| `similaritySearch(query, k)` | Return top-K similar documents |
| `asRetriever()` | Wrap as Retriever |
| `delete(ids)` | Delete by IDs |

---

## 9. TTS

**Package**: `org.salt.jlangchain.ai.tts`

| Class | Env Variable | Description |
|-------|--------------|-------------|
| `AliyunTts` | `ALIYUN_TTS_KEY` | Alibaba Cloud TTS |
| `DoubaoTts` | `DOUBAO_TTS_KEY` | Doubao TTS |

```java
AliyunTts tts = AliyunTts.builder()
    .appKey("your-app-key")
    .voice("xiaoyun")
    .format("wav")
    .build();

TtsCardChunk audio = tts.stream("Hello, welcome to J-LangChain!");
// Bracket content auto-filtered before synthesis
```

---

## 10. Flow Orchestration

Chain building is powered by [salt-function-flow](https://github.com/flower-trees/salt-function-flow):

| Concept | Description |
|---------|-------------|
| `.next(runnable)` | Next sequential node |
| `.concurrent(chain1, chain2, ...)` | Parallel execution |
| `cAlias("name", chain)` | Named parallel branch (result accessible by alias) |
| `Info.c("condition", runnable)` | Conditional routing (SpEL expressions) |
| `Info.c(runnable)` | Default branch |
| `FlowInstance` | Built flow instance |

---

## 11. Message & Result Types

| Class | Package | Description |
|-------|---------|-------------|
| `AIMessage` | `core.message` | AI reply |
| `HumanMessage` | `core.message` | User message |
| `AIMessageChunk` | `core.message` | Streaming AI chunk |
| `ToolMessage` | `core.message` | Tool call result |
| `EventMessageChunk` | `core.event` | Event stream chunk |
| `TtsCardChunk` | `ai.tts` | TTS audio stream chunk |
| `ChatGeneration` | `core.parser.generation` | Sync generation result |
| `ChatGenerationChunk` | `core.parser.generation` | Streaming generation chunk |

---

## 12. Environment Variables

| Variable | LLM Class | Description |
|----------|-----------|-------------|
| `CHATGPT_KEY` | `ChatOpenAI` | OpenAI API key |
| `OLLAMA_KEY1` | `ChatOllama` | Ollama (usually empty for local) |
| `ALIYUN_KEY` | `ChatAliyun` | Alibaba Cloud Qwen |
| `MOONSHOT_KEY` | `ChatMoonshot` | Moonshot (Kimi) |
| `DOUBAO_KEY` | `ChatDoubao` | Doubao (ByteDance) |
| `COZE_KEY` | `ChatCoze` | Coze API key |
| `COZE_CLIENT_ID` | `ChatCoze` | Coze OAuth 2.0 client ID |
| `COZE_PRIVATE_KEY_PATH` | `ChatCoze` | Coze OAuth 2.0 private key path |
| `COZE_PUBLIC_KEY_ID` | `ChatCoze` | Coze OAuth 2.0 public key ID |
| `DEEPSEEK_KEY` | `ChatDeepseek` | DeepSeek API key |
| `HUNYUAN_KEY` | `ChatHunyuan` | Tencent Hunyuan |
| `QIANFAN_KEY` | `ChatQianfan` | Baidu Qianfan (ERNIE) |
| `ZHIPU_KEY` | `ChatZhipu` | Zhipu AI (GLM) |
| `MINIMAX_KEY` | `ChatMinimax` | MiniMax |
| `LINGYI_KEY` | `ChatLingyi` | 01.AI (Yi) |
| `STEPFUN_KEY` | `ChatStepfun` | StepFun |
| `ALIYUN_TTS_KEY` | `AliyunTts` | Alibaba Cloud TTS |
| `DOUBAO_TTS_KEY` | `DoubaoTts` | Doubao TTS |

All variables can alternatively be set in `application.yml`:

```yaml
models:
  aliyun:
    chat-key: ${ALIYUN_KEY}
  chatgpt:
    chat-key: ${CHATGPT_KEY}
  deepseek:
    chat-key: ${DEEPSEEK_KEY}
  # ... same pattern for all vendors
tts:
  aliyun:
    api-key: ${ALIYUN_TTS_KEY}
  doubao:
    api-key: ${DOUBAO_TTS_KEY}
rag:
  vector:
    milvus:
      use: true
```

---

**Related**: [Quick Start](../guide/quickstart.md) · [Tutorial Series](../article/001-en/README.md) · [Sample Code](../../src/test/java/org/salt/jlangchain/demo/)
