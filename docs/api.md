# J-LangChain API Reference

This document describes the APIs of J-LangChain core components. All packages use the prefix `org.salt.jlangchain`.

---

## 1. ChainActor (Chain Orchestration)

**Package**: `org.salt.jlangchain.core.ChainActor`

Orchestrates Prompt, LLM, Parser nodes to build executable chains.

### Methods

| Method | Description | Returns |
|--------|-------------|---------|
| `builder()` | Get chain builder | `FlowEngine.Builder` |
| `invoke(FlowInstance flow, I input)` | Execute chain synchronously | Output type `O` |
| `invoke(FlowInstance flow, I input, Map<String,Object> transmitMap)` | Execute with pass-through params | Output type `O` |
| `stream(FlowInstance flow, I input)` | Execute chain in streaming mode | Streaming output (e.g. `ChatGenerationChunk`) |
| `streamEvent(FlowInstance flow, I input)` | Event stream execution, emit node events | `EventMessageChunk` |
| `streamEvent(flow, input, filter)` | Event stream with filter | `EventMessageChunk` |
| `stop(FlowInstance flowInstance)` | Stop chain execution | void |

### Example

```java
FlowInstance chain = chainActor.builder()
    .next(prompt)
    .next(llm)
    .next(new StrOutputParser())
    .build();

ChatGeneration result = chainActor.invoke(chain, Map.of("topic", "AI"));
```

---

## 2. LLM Models (BaseChatModel)

**Base class**: `org.salt.jlangchain.core.llm.BaseChatModel`

All LLM implementations extend this class. Support `invoke`, `stream`, `streamEvent`.

### 2.1 ChatOpenAI

**Package**: `org.salt.jlangchain.core.llm.openai.ChatOpenAI`

| Param | Type | Default | Description |
|-------|------|---------|-------------|
| model | String | gpt-4 | Model name |
| temperature | Float | 0.7 | Temperature |
| tools | List\<AiChatInput.Tool\> | - | Tool calling list |

```java
ChatOpenAI llm = ChatOpenAI.builder()
    .model("gpt-4")
    .temperature(0.7f)
    .build();
```

### 2.2 ChatAliyun (Alibaba Cloud Qwen)

**Package**: `org.salt.jlangchain.core.llm.aliyun.ChatAliyun`

| Param | Type | Default | Description |
|-------|------|---------|-------------|
| model | String | qwq-32b-preview | e.g. qwen-plus, qwen-turbo |
| temperature | Float | 0.7 | Temperature |
| tools | List\<AiChatInput.Tool\> | - | Tool calling |

```java
ChatAliyun llm = ChatAliyun.builder()
    .model("qwen-plus")
    .build();
```

### 2.3 ChatOllama

**Package**: `org.salt.jlangchain.core.llm.ollama.ChatOllama`

| Param | Type | Default | Description |
|-------|------|---------|-------------|
| model | String | qwen2.5:0.5b | e.g. llama3:8b |
| temperature | Float | 0.7 | Temperature |
| tools | List\<AiChatInput.Tool\> | - | Tool calling |

```java
ChatOllama llm = ChatOllama.builder()
    .model("qwen2.5:0.5b")
    .build();
```

### 2.4 Other Models

| Class | Package | Description |
|-------|---------|-------------|
| ChatMoonshot | core.llm.moonshot | Kimi / Moonshot |
| ChatDoubao | core.llm.doubao | Doubao |
| ChatCoze | core.llm.doubao | Coze |

### 2.5 Common Methods

| Method | Description | Returns |
|--------|-------------|---------|
| `invoke(Object input)` | Invoke synchronously | `AIMessage` |
| `stream(Object input)` | Invoke in streaming mode | `AIMessageChunk` |
| `streamEvent(Object input)` | Event stream | `EventMessageChunk` |
| `withConfig(Map<String,Object> config)` | Attach config (run_name, tags, etc.) | `BaseChatModel` |

**Supported input**: `String`, `StringPromptValue`, `ChatPromptValue`

---

## 3. Prompt Templates

### 3.1 PromptTemplate

**Package**: `org.salt.jlangchain.core.prompt.string.PromptTemplate`

Simple string template with `${variable}` placeholders.

| Method | Description |
|--------|-------------|
| `fromTemplate(String template)` | Static factory to create template |

```java
PromptTemplate prompt = PromptTemplate.fromTemplate("Tell me about ${topic}");
// Input: Map.of("topic", "AI") → Output: "Tell me about AI"
```

### 3.2 ChatPromptTemplate

**Package**: `org.salt.jlangchain.core.prompt.chat.ChatPromptTemplate`

Multi-turn dialogue template supporting system/human/ai roles.

| Method | Description |
|--------|-------------|
| `fromMessages(List<?> messages)` | Static factory to create chat template |

```java
ChatPromptTemplate prompt = ChatPromptTemplate.fromMessages(List.of(
    Pair.of("system", "You are an assistant."),
    Pair.of("human", "${question}")
));
```

---

## 4. Output Parsers

### 4.1 StrOutputParser

**Package**: `org.salt.jlangchain.core.parser.StrOutputParser`

Converts `AIMessage` to String.

```java
.next(new StrOutputParser())
```

### 4.2 JsonOutputParser

**Package**: `org.salt.jlangchain.core.parser.JsonOutputParser`

Parses LLM output as JSON (supports incremental parsing).

```java
.next(new JsonOutputParser())
```

### 4.3 FunctionOutputParser

**Package**: `org.salt.jlangchain.core.parser.FunctionOutputParser`

Parses streaming output with custom function.

```java
.next(new FunctionOutputParser(this::extractField))
```

---

## 5. RAG

### 5.1 Document

**Package**: `org.salt.jlangchain.rag.media.Document`

| Field | Type | Description |
|-------|------|-------------|
| pageContent | String | Text content |
| fileId | Long | File ID |

### 5.2 Document Loaders

| Class | Package | Description |
|-------|---------|-------------|
| PdfboxLoader | rag.loader.pdf | PDF loading |
| ApachePoiDocxLoader | rag.loader.docx | Word DOCX loading |

```java
PdfboxLoader loader = PdfboxLoader.builder()
    .filePath("path/to/file.pdf")
    .build();
List<Document> docs = loader.load();
```

### 5.3 Text Splitting

| Class | Package | Description |
|-------|---------|-------------|
| StanfordNLPTextSplitter | rag.splitter | Chunking based on Stanford NLP |

```java
StanfordNLPTextSplitter splitter = StanfordNLPTextSplitter.builder()
    .chunkSize(500)
    .chunkOverlap(50)
    .build();
List<Document> chunks = splitter.splitDocument(docs);
```

### 5.4 Embeddings

**Base class**: `org.salt.jlangchain.rag.embedding.Embeddings`

| Class | Package | Description |
|-------|---------|-------------|
| OllamaEmbeddings | rag.embedding | Ollama embeddings |
| OpenAIEmbeddings | rag.embedding | OpenAI embeddings |
| AliyunEmbeddings | rag.embedding | Alibaba Cloud embeddings |

```java
OllamaEmbeddings embeddings = OllamaEmbeddings.builder()
    .model("nomic-embed-text")
    .vectorSize(768)
    .build();
List<List<Float>> vectors = embeddings.embedDocuments(texts);
List<Float> queryVector = embeddings.embedQuery("query text");
```

### 5.5 VectorStore

**Base class**: `org.salt.jlangchain.rag.vector.VectorStore`

| Method | Description |
|--------|-------------|
| addText(texts, metadatas, ids, fileId) | Add texts |
| addDocument(documents, fileId) | Add documents |
| similaritySearch(query, k) | Similarity search, return top K |
| asRetriever() | Convert to Retriever |
| delete(ids) | Delete by IDs |
| getByIds(ids) | Get by IDs |

**Milvus implementation**: `org.salt.jlangchain.rag.vector.Milvus`

```java
VectorStore vectorStore = Milvus.fromDocuments(
    documents,
    embeddings,
    "collection_name"
);
List<Document> results = vectorStore.similaritySearch("query", 3);
```

---

## 6. MCP Tool Calling

### 6.1 McpClient

**Package**: `org.salt.jlangchain.rag.tools.mcp.McpClient`

| Constructor | Description |
|-------------|-------------|
| McpClient() | Load `mcp.server.config.json` from classpath |
| McpClient(String configPath) | Specify config file path |

| Method | Description | Returns |
|--------|-------------|---------|
| loadConfig(String configPath) | Load config | McpConfig |
| initializeFromConfig(McpConfig config) | Initialize connections from config | void |
| listAllTools() | List tools from all MCP servers | Map\<String, List\<ToolDesc\>\> |
| callTool(serverName, toolName, arguments) | Call specified tool | ToolResult |
| getServerStatuses() | Get connection status per server | Map\<String, ServerStatus\> |

### 6.2 McpManager

**Package**: `org.salt.jlangchain.rag.tools.mcp.McpManager`

Wrapper of McpClient with `manifest()` and other convenience methods for LLM tool injection.

---

## 7. Message Types

| Class | Package | Description |
|-------|---------|-------------|
| AIMessage | core.message | AI reply |
| HumanMessage | core.message | User message |
| BaseMessage | core.message | Base class |
| AIMessageChunk | core.message | Streaming AI chunk |
| ToolMessage | core.message | Tool call result |

---

## 8. Generation Result Types

| Class | Package | Description |
|-------|---------|-------------|
| ChatGeneration | core.parser.generation | Sync generation result |
| Generation | core.parser.generation | Base class |
| ChatGenerationChunk | core.parser.generation | Streaming generation chunk |

---

## 9. Flow Orchestration (salt-function-flow)

Chain building uses [salt-function-flow](https://github.com/flower-trees/salt-function-flow):

| Concept | Description |
|---------|-------------|
| FlowInstance | Flow instance |
| Info.c("condition", handler) | Conditional routing, SpEL supported |
| .concurrent(chain1, chain2...) | Execute multiple chains in parallel |
| .next(handler) | Next node in sequence |

---

## 10. Configuration

### Environment Variables

See [README - Set API Key](../README.md#3️⃣-set-api-key).

### application.yml Example

```yaml
models:
  aliyun:
    chat-key: ${ALIYUN_KEY}
  chatgpt:
    chat-key: ${CHATGPT_KEY}
rag:
  vector:
    milvus:
      use: true
```

---

**Related**: [Quick Start](./quickstart.md) | [Sample Code](../src/test/java/org/salt/jlangchain/demo/)
