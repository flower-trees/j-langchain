# J-LangChain API 参考

所有包的前缀为 `org.salt.jlangchain`。完整 Javadoc 可通过 IDE 或 `mvn javadoc:javadoc` 生成。

---

## 1. ChainActor

**包路径**: `org.salt.jlangchain.core.ChainActor`

链的核心入口，负责构建和执行 Prompt → LLM → Parser 调用链。

| 方法 | 说明 | 返回 |
|------|------|------|
| `builder()` | 获取 FlowEngine.Builder | `FlowEngine.Builder` |
| `invoke(flow, input)` | 同步执行 | 输出类型 `O` |
| `invoke(flow, input, transmitMap)` | 带透传参数的同步执行 | 输出类型 `O` |
| `stream(flow, input)` | 流式执行 | `ChatGenerationChunk` |
| `streamEvent(flow, input)` | 事件流执行 | `EventMessageChunk` |
| `streamEvent(flow, input, filter)` | 带过滤器的事件流 | `EventMessageChunk` |
| `stop(flowInstance)` | 停止链执行 | void |

```java
FlowInstance chain = chainActor.builder()
    .next(prompt)
    .next(llm)
    .next(new StrOutputParser())
    .build();

String result = chainActor.invoke(chain, Map.of("topic", "AI"));
```

---

## 2. 大模型（LLM）

**基类**: `org.salt.jlangchain.core.llm.BaseChatModel`

所有实现类均支持 `invoke`、`stream`、`streamEvent`、`withConfig`。

**支持的输入类型**: `String`、`StringPromptValue`、`ChatPromptValue`

### 2.1 所有支持的模型

| 类名 | 包路径 | 环境变量 | 说明 |
|------|--------|----------|------|
| `ChatOpenAI` | `core.llm.openai` | `CHATGPT_KEY` | OpenAI GPT-4 / GPT-3.5 |
| `ChatOllama` | `core.llm.ollama` | `OLLAMA_KEY1` | 本地开源模型 |
| `ChatAliyun` | `core.llm.aliyun` | `ALIYUN_KEY` | 阿里云千问 |
| `ChatMoonshot` | `core.llm.moonshot` | `MOONSHOT_KEY` | Moonshot (Kimi) |
| `ChatDoubao` | `core.llm.doubao` | `DOUBAO_KEY` | 豆包（字节跳动） |
| `ChatCoze` | `core.llm.doubao` | `COZE_KEY` | 扣子 |
| `ChatDeepseek` | `core.llm.deepseek` | `DEEPSEEK_KEY` | DeepSeek-V3 / R1 |
| `ChatHunyuan` | `core.llm.hunyuan` | `HUNYUAN_KEY` | 腾讯混元 |
| `ChatQianfan` | `core.llm.qianfan` | `QIANFAN_KEY` | 百度文心（ERNIE） |
| `ChatZhipu` | `core.llm.zhipu` | `ZHIPU_KEY` | 智谱 GLM |
| `ChatMinimax` | `core.llm.minimax` | `MINIMAX_KEY` | MiniMax |
| `ChatLingyi` | `core.llm.lingyi` | `LINGYI_KEY` | 零一万物 Yi |
| `ChatStepfun` | `core.llm.stepfun` | `STEPFUN_KEY` | 阶跃星辰 |

### 2.2 通用 Builder 参数

| 参数 | 类型 | 默认值 | 说明 |
|------|------|--------|------|
| `model` | String | 厂商默认 | 模型名，如 `qwen-plus`、`gpt-4` |
| `temperature` | Float | 0.7 | 采样温度 |
| `tools` | `List<AiChatInput.Tool>` | — | 工具调用定义列表 |

```java
ChatAliyun llm = ChatAliyun.builder()
    .model("qwen-plus")
    .temperature(0.7f)
    .build();

AIMessage response = llm.invoke("你好");
AIMessageChunk stream = llm.stream("给我讲个故事");
```

### 2.3 扣子 OAuth 2.0

```bash
# COZE_KEY 的替代方案
export COZE_CLIENT_ID=xxx
export COZE_PRIVATE_KEY_PATH=/path/to/private-key.pem
export COZE_PUBLIC_KEY_ID=xxx
```

---

## 3. Prompt 模板

### 3.1 PromptTemplate

**包路径**: `org.salt.jlangchain.core.prompt.string.PromptTemplate`

字符串模板，支持 `${variable}` 变量替换。

```java
PromptTemplate prompt = PromptTemplate.fromTemplate("请介绍一下 ${topic}");
// 输入: Map.of("topic","AI") → 输出: "请介绍一下 AI"
```

### 3.2 ChatPromptTemplate

**包路径**: `org.salt.jlangchain.core.prompt.chat.ChatPromptTemplate`

多轮对话模板，支持 `system` / `human` / `ai` 角色。

```java
ChatPromptTemplate prompt = ChatPromptTemplate.fromMessages(List.of(
    Pair.of("system", "你是一个助手。背景知识：${context}"),
    Pair.of("human", "${question}")
));
```

---

## 4. 输出解析器

| 类 | 包路径 | 说明 |
|----|--------|------|
| `StrOutputParser` | `core.parser` | `AIMessage` → `String` |
| `JsonOutputParser` | `core.parser` | LLM 输出 → 解析 JSON |
| `FunctionOutputParser` | `core.parser` | 自定义函数解析流式块 |

---

## 5. Agent 与工具调用

### 5.1 AgentExecutor

**包路径**: `org.salt.jlangchain.core.agent.AgentExecutor`

封装 ReAct（推理 + 行动）循环，自动调用工具直至得出最终答案。

| Builder 参数 | 类型 | 说明 |
|--------------|------|------|
| `llm` | `BaseChatModel` | 用于推理的大模型 |
| `toolScanner` | `ToolScanner` | 扫描 `@AgentTool` 方法 |
| `maxIterations` | int | 最大 ReAct 循环次数（默认 10） |
| `onThought` | `Consumer<String>` | 每步推理的回调 |
| `onObservation` | `Consumer<String>` | 工具结果的回调 |
| `onLlm` | `Consumer<String>` | 模型调用前的回调（1.0.14 新增） |

```java
AgentExecutor agent = AgentExecutor.builder()
    .llm(ChatAliyun.builder().model("qwen-plus").build())
    .toolScanner(new ToolScanner(this))
    .build();

String result = agent.invoke("帮我查北京到上海的机票");
```

### 5.2 McpAgentExecutor

**包路径**: `org.salt.jlangchain.core.agent.McpAgentExecutor`

模型侧 Function Calling + MCP 工具编排，使用原生工具调用消息而非 ReAct 提示词。

```java
McpAgentExecutor agent = McpAgentExecutor.builder()
    .llm(ChatAliyun.builder().model("qwen-plus").build())
    .mcpManager(mcpManager)
    .build();
```

### 5.3 @AgentTool / @Param / @ParamDesc / ToolScanner

**包路径**: `org.salt.jlangchain.rag.tools.annotation`

通过注解声明工具方法：

```java
@AgentTool(description = "查询两城市间的机票价格")
public String searchFlights(
    @Param("出发城市") String from,
    @Param("目的城市") String to) {
    return from + "→" + to + "：经济舱 ¥999";
}
```

**`@AgentTool`** — 标记方法为 AI 工具。

| 属性 | 说明 |
|------|------|
| `description` | 展示给大模型的工具描述 |
| `params` | `@ParamDesc[]` 数组，用于第三方 VO 参数描述（1.0.14+） |

**`@Param`** — 标注方法参数或 VO 字段（1.0.14+ 支持 `ElementType.FIELD`）。

**`@ParamDesc`** — 用于无法直接修改的第三方对象 VO 字段描述。优先级：`@AgentTool.params` > VO 字段 `@Param` > 方法参数 `@Param`。

```java
// RPC 零侵入：不修改原有类，通过 @ParamDesc 描述 VO 字段
@AgentTool(
    description = "查询订单",
    params = {
        @ParamDesc(name = "orderId", description = "订单ID"),
        @ParamDesc(name = "userId",  description = "用户ID")
    }
)
public OrderVO queryOrder(OrderQuery query) { ... }
```

**`ToolScanner`** — 扫描 Bean 中的 `@AgentTool` 方法，自动为参数生成 JSON Schema（1.0.14+ 支持复杂对象类型）。

```java
ToolScanner scanner = new ToolScanner(myToolBean);
```

---

## 6. MCP 协议

### 6.1 McpClient

**包路径**: `org.salt.jlangchain.rag.tools.mcp.McpClient`

| 构造方法 | 说明 |
|----------|------|
| `McpClient()` | 从 classpath 加载 `mcp.server.config.json` |
| `McpClient(String configPath)` | 指定配置文件路径 |

| 方法 | 说明 | 返回 |
|------|------|------|
| `listAllTools()` | 获取所有服务器的工具列表 | `Map<String, List<ToolDesc>>` |
| `callTool(serverName, toolName, arguments)` | 调用指定工具 | `ToolResult` |
| `getServerStatuses()` | 获取各服务器连接状态 | `Map<String, ServerStatus>` |
| `destroy()` | 关闭所有连接 | void |

配置文件支持环境变量占位符：`${VAR_NAME}` 或 `${VAR_NAME:default}`。

### 6.2 McpManager

**包路径**: `org.salt.jlangchain.rag.tools.mcp.McpManager`

`McpClient` 的高级封装，提供 `manifest()` 和 `manifestForInput()` 用于向大模型注入工具列表。

```java
List<AiChatInput.Tool> tools = mcpManager.manifest();          // 获取所有工具
List<AiChatInput.Tool> tools = mcpManager.manifestForInput();  // 转换为 LLM 输入格式
```

---

## 7. 历史管理

**包路径**: `org.salt.jlangchain.core.history`

多应用对话历史管理，线程安全（1.0.14+）。

### HistoryBase

| 字段 | 说明 |
|------|------|
| `appId` | 应用标识（多应用隔离） |
| `userId` | 用户标识 |
| `sessionId` | 会话标识 |

### MemoryHistory

基于三层嵌套 `ConcurrentHashMap`（`appId → userId → sessionId`）的内存历史存储。

| Builder 参数 | 说明 |
|--------------|------|
| `appId` | 应用 ID |
| `maxSize` | 每会话最大历史条数（默认不限） |

```java
MemoryHistory history = MemoryHistory.builder()
    .appId("my-app")
    .maxSize(20)
    .build();
```

### MemoryHistoryReader / MemoryHistoryStorer

线程安全的读取器和存储器，配合 LLM 节点实现对话记忆：

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

### 8.1 文档加载器

| 类 | 包路径 | 说明 |
|----|--------|------|
| `PdfboxLoader` | `rag.loader.pdf` | PDF 加载 |
| `ApachePoiDocxLoader` | `rag.loader.docx` | Word DOCX |
| `ApachePoiDocLoader` | `rag.loader.doc` | Word DOC（旧格式） |

```java
PdfboxLoader loader = PdfboxLoader.builder().filePath("doc.pdf").build();
List<Document> docs = loader.load();
```

### 8.2 文本分割

```java
StanfordNLPTextSplitter splitter = StanfordNLPTextSplitter.builder()
    .chunkSize(500)
    .chunkOverlap(50)
    .build();
List<Document> chunks = splitter.splitDocument(docs);
```

### 8.3 向量嵌入

| 类 | 环境变量 | 说明 |
|----|----------|------|
| `OllamaEmbeddings` | `OLLAMA_KEY1` | Ollama 本地 |
| `OpenAIEmbeddings` | `CHATGPT_KEY` | OpenAI |
| `AliyunEmbeddings` | `ALIYUN_KEY` | 阿里云 |

```java
OllamaEmbeddings embeddings = OllamaEmbeddings.builder()
    .model("nomic-embed-text")
    .vectorSize(768)
    .build();
```

### 8.4 向量数据库（Milvus）

```java
Milvus vectorStore = Milvus.fromDocuments(docs, embeddings, "collection");
List<Document> results = vectorStore.similaritySearch("查询内容", 3);
```

| 方法 | 说明 |
|------|------|
| `addDocuments(docs)` | 添加文档 |
| `similaritySearch(query, k)` | 返回最相似的 K 个文档 |
| `asRetriever()` | 转为 Retriever |
| `delete(ids)` | 按 ID 删除 |

---

## 9. TTS 语音合成

**包路径**: `org.salt.jlangchain.ai.tts`

| 类 | 环境变量 | 说明 |
|----|----------|------|
| `AliyunTts` | `ALIYUN_TTS_KEY` | 阿里云语音合成 |
| `DoubaoTts` | `DOUBAO_TTS_KEY` | 豆包语音合成 |

```java
AliyunTts tts = AliyunTts.builder()
    .appKey("your-app-key")
    .voice("xiaoyun")
    .format("wav")
    .build();

TtsCardChunk audio = tts.stream("你好，欢迎使用 J-LangChain！");
// 括号内容自动过滤后再合成
```

---

## 10. 链式编排

基于 [salt-function-flow](https://github.com/flower-trees/salt-function-flow)：

| 方法 | 说明 |
|------|------|
| `.next(runnable)` | 串行下一节点 |
| `.concurrent(chain1, chain2, ...)` | 并行执行 |
| `cAlias("name", chain)` | 具名并行分支（可按名称取结果） |
| `Info.c("condition", runnable)` | 条件路由（SpEL 表达式） |
| `Info.c(runnable)` | 默认分支 |
| `FlowInstance` | 构建完成的流实例 |

---

## 11. 消息与结果类型

| 类 | 包路径 | 说明 |
|----|--------|------|
| `AIMessage` | `core.message` | AI 回复 |
| `HumanMessage` | `core.message` | 用户消息 |
| `AIMessageChunk` | `core.message` | 流式 AI 块 |
| `ToolMessage` | `core.message` | 工具调用结果 |
| `EventMessageChunk` | `core.event` | 事件流块 |
| `TtsCardChunk` | `ai.tts` | TTS 流式音频块 |
| `ChatGeneration` | `core.parser.generation` | 同步生成结果 |
| `ChatGenerationChunk` | `core.parser.generation` | 流式生成块 |

---

## 12. 环境变量

| 变量名 | 对应类 | 说明 |
|--------|--------|------|
| `CHATGPT_KEY` | `ChatOpenAI` | OpenAI API Key |
| `OLLAMA_KEY1` | `ChatOllama` | Ollama（本地通常留空） |
| `ALIYUN_KEY` | `ChatAliyun` | 阿里云千问 |
| `MOONSHOT_KEY` | `ChatMoonshot` | Moonshot (Kimi) |
| `DOUBAO_KEY` | `ChatDoubao` | 豆包 |
| `COZE_KEY` | `ChatCoze` | 扣子 API Key |
| `COZE_CLIENT_ID` | `ChatCoze` | 扣子 OAuth 2.0 Client ID |
| `COZE_PRIVATE_KEY_PATH` | `ChatCoze` | 扣子 OAuth 2.0 私钥路径 |
| `COZE_PUBLIC_KEY_ID` | `ChatCoze` | 扣子 OAuth 2.0 公钥 ID |
| `DEEPSEEK_KEY` | `ChatDeepseek` | DeepSeek |
| `HUNYUAN_KEY` | `ChatHunyuan` | 腾讯混元 |
| `QIANFAN_KEY` | `ChatQianfan` | 百度千帆（文心） |
| `ZHIPU_KEY` | `ChatZhipu` | 智谱 AI（GLM） |
| `MINIMAX_KEY` | `ChatMinimax` | MiniMax |
| `LINGYI_KEY` | `ChatLingyi` | 零一万物（Yi） |
| `STEPFUN_KEY` | `ChatStepfun` | 阶跃星辰 |
| `ALIYUN_TTS_KEY` | `AliyunTts` | 阿里云语音合成 |
| `DOUBAO_TTS_KEY` | `DoubaoTts` | 豆包语音合成 |

所有变量均可在 `application.yml` 中配置：

```yaml
models:
  aliyun:
    chat-key: ${ALIYUN_KEY}
  chatgpt:
    chat-key: ${CHATGPT_KEY}
  deepseek:
    chat-key: ${DEEPSEEK_KEY}
  # 其他厂商同理
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

**相关链接**: [快速开始](../guide/quickstart_cn.md) · [中文教程系列](../article/001/README.md) · [示例代码](../../src/test/java/org/salt/jlangchain/demo/)
