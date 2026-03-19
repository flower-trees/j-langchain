# J-LangChain API 参考

本文档提供 J-LangChain 核心 API 的简要参考。完整 Javadoc 可通过 IDE 或 `mvn javadoc:javadoc` 生成。

---

## 1. 核心编排：ChainActor

入口类，负责链的构建与执行。

```java
org.salt.jlangchain.core.ChainActor
```

| 方法 | 说明 |
|------|------|
| `builder()` | 获取 FlowEngine.Builder，用于构建链 |
| `invoke(FlowInstance flow, I input)` | 同步执行，返回最终结果 |
| `invoke(FlowInstance flow, I input, Map<String,Object> transmitMap)` | 带透传参数的同步执行 |
| `stream(FlowInstance flow, I input)` | 流式执行，返回可迭代的 Chunk |
| `streamEvent(FlowInstance flow, I input)` | 事件流执行，返回 EventMessageChunk |
| `streamEvent(flow, input, Function<EventMessageChunk,Boolean> filter)` | 带过滤条件的事件流 |
| `stop(FlowInstance flowInstance)` | 停止链执行 |

---

## 2. Prompt 模板

### PromptTemplate

字符串模板，支持 `${variable}` 变量替换。

```java
org.salt.jlangchain.core.prompt.string.PromptTemplate
```

| 方法 | 说明 |
|------|------|
| `fromTemplate(String template)` | 创建模板 |

**输入**：`Map<String, ?>` 或 PlainObject  
**输出**：`StringPromptValue`

### ChatPromptTemplate

多轮对话模板（system / human / ai）。

```java
org.salt.jlangchain.core.prompt.chat.ChatPromptTemplate
```

| 方法 | 说明 |
|------|------|
| `fromMessages(List<?> messages)` | 创建模板，如 `Pair.of("system","...")`、`Pair.of("human","...")` |

**输入**：`Map<String, ?>`  
**输出**：`ChatPromptValue`

---

## 3. LLM 大模型

均继承 `BaseChatModel`，支持 `invoke`、`stream`、`streamEvent`。

### ChatOpenAI

```java
org.salt.jlangchain.core.llm.openai.ChatOpenAI
ChatOpenAI.builder().model("gpt-4").temperature(0.7f).tools(tools).build()
```

| 属性 | 说明 |
|------|------|
| model | 模型名，如 gpt-4、gpt-3.5-turbo |
| temperature | 温度，默认 0.7 |
| tools | 工具调用列表 |

### ChatAliyun

```java
org.salt.jlangchain.core.llm.aliyun.ChatAliyun
ChatAliyun.builder().model("qwen-plus").build()
```

### ChatOllama

```java
org.salt.jlangchain.core.llm.ollama.ChatOllama
ChatOllama.builder().model("qwen2.5:0.5b").build()
```

### ChatMoonshot / ChatDoubao / ChatCoze

```java
org.salt.jlangchain.core.llm.moonshot.ChatMoonshot
org.salt.jlangchain.core.llm.doubao.ChatDoubao
org.salt.jlangchain.core.llm.doubao.ChatCoze
```

---

## 4. 输出解析器

### StrOutputParser

```java
org.salt.jlangchain.core.parser.StrOutputParser
```

将 `AIMessage` 转为字符串。

### JsonOutputParser

```java
org.salt.jlangchain.core.parser.JsonOutputParser
```

将 LLM 输出的 JSON 文本解析为结构化数据。

### FunctionOutputParser

```java
org.salt.jlangchain.core.parser.FunctionOutputParser
new FunctionOutputParser(chunk -> extractedValue)
```

通过自定义函数解析流式块。

---

## 5. 消息类型

| 类 | 说明 |
|----|------|
| `AIMessage` | AI 回复 |
| `HumanMessage` | 用户消息 |
| `BaseMessage` | 基类 |
| `AIMessageChunk` | 流式 AI 块 |
| `ToolMessage` | 工具调用结果 |

---

## 6. RAG

### 文档加载

| 类 | 说明 |
|----|------|
| `PdfboxLoader` | PDF，`PdfboxLoader.builder().filePath(path).build()` |
| `ApachePoiDocxLoader` | Word DOCX |

### 文本分割

```java
org.salt.jlangchain.rag.splitter.StanfordNLPTextSplitter
StanfordNLPTextSplitter.builder().chunkSize(500).chunkOverlap(50).build()
List<Document> chunks = splitter.splitDocument(docs);
```

### 向量嵌入

```java
org.salt.jlangchain.rag.embedding.OllamaEmbeddings
org.salt.jlangchain.rag.embedding.OpenAIEmbeddings
org.salt.jlangchain.rag.embedding.AliyunEmbeddings

embeddings.embedDocuments(List<String> texts)  -> List<List<Float>>
embeddings.embedQuery(String text)             -> List<Float>
```

### 向量数据库

```java
org.salt.jlangchain.rag.vector.Milvus
org.salt.jlangchain.rag.vector.VectorStore
```

| 方法 | 说明 |
|------|------|
| `Milvus.fromDocuments(docs, embeddings, collectionName)` | 从文档创建并写入 |
| `similaritySearch(String query, int k)` | 相似度检索 |
| `asRetriever()` | 转为 Retriever |

### Document

```java
org.salt.jlangchain.rag.media.Document
```

| 属性 | 说明 |
|------|------|
| pageContent | 文本内容 |
| metadata | 元数据 |
| fileId | 文件 ID |

---

## 7. MCP 工具调用

### McpClient

```java
org.salt.jlangchain.rag.tools.mcp.McpClient

McpClient mcpClient = new McpClient("path/to/mcp-config.json");
```

| 方法 | 说明 |
|------|------|
| `loadConfig(String configPath)` | 加载配置 |
| `initializeFromConfig(McpConfig config)` | 按配置初始化连接 |
| `listAllTools()` | 获取所有服务器的工具列表 `Map<String, List<ToolDesc>>` |
| `callTool(String serverName, String toolName, Map<String,Object> arguments)` | 调用工具 |
| `getServerStatuses()` | 获取各 MCP 服务器状态 |
| `destroy()` | 关闭连接（DisposableBean） |

配置文件支持环境变量占位：`${VAR_NAME}` 或 `${VAR_NAME:default}`。

---

## 8. 流式与事件

| 返回类型 | 说明 |
|----------|------|
| `AIMessageChunk` | 流式 Token，`getIterator()` 迭代 |
| `ChatGenerationChunk` | 解析后的流式块 |
| `EventMessageChunk` | 事件流，`toJson()` 输出事件 |
| `TtsCardChunk` | TTS 流式音频块 |

---

## 9. salt-function-flow 链构建

链由 `FlowEngine.builder()` 构建，常用节点：

| 方法 | 说明 |
|------|------|
| `next(runnable)` | 串行下一节点 |
| `concurrent(chain1, chain2, ...)` | 并行执行 |
| `Info.c("condition", runnable)` | 条件路由（SpEL） |
| `Info.c(runnable)` | 默认分支 |

详见 [salt-function-flow](https://github.com/flower-trees/salt-function-flow)。

---

## 相关链接

- [快速开始](../guide/quickstart_cn.md)
- [示例代码](../../src/test/java/org/salt/jlangchain/demo/)
- [salt-function-flow](https://github.com/flower-trees/salt-function-flow)
