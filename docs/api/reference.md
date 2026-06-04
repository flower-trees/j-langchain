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
| `modelKwargs` | `Map<String,Object>` | — | Vendor-specific extra parameters (e.g. `enable_thinking`) |

```java
ChatAliyun llm = ChatAliyun.builder()
    .model("qwen-plus")
    .temperature(0.7f)
    .build();

AIMessage response = llm.invoke("Hello");
AIMessageChunk stream = llm.stream("Tell me a story");
```

### 2.3 Reasoning Models

Reasoning models produce a chain-of-thought before the final answer. Both parts are accessible via `AIMessage` (and streaming `AIMessageChunk`).

| Model | How to enable |
|-------|--------------|
| DeepSeek-R1 (`deepseek-reasoner`) | Reasoning on by default |
| Qwen3 | Pass `modelKwargs(Map.of("enable_thinking", true))` |

```java
// Non-streaming: reasoning + answer in one call
ChatDeepseek llm = ChatDeepseek.builder().model("deepseek-reasoner").build();
AIMessage result = llm.invoke("Which is larger: 9.11 or 9.9?");
String reasoning = result.getReasoningContent(); // chain-of-thought
String answer    = result.getContent();           // final answer

// Streaming: reasoning tokens arrive first, then answer tokens
AIMessageChunk stream = llm.stream("Which is larger: 9.11 or 9.9?");
while (stream.getIterator().hasNext()) {
    AIMessageChunk chunk = stream.getIterator().next();
    if (chunk.getReasoningContent() != null) { /* reasoning delta */ }
    if (chunk.getContent() != null)          { /* answer delta    */ }
}
// Accumulated values after stream ends
String fullReasoning = stream.getReasoningContent();
String fullAnswer    = stream.getContent();
```

> `getReasoningContent()` returns `null` for non-reasoning models — existing code is unaffected.

### 2.4 Coze OAuth 2.0

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

Model-side Function Calling with MCP tool orchestration. Uses native tool-call messages instead of ReAct prompting. Supports registering Skills and SubAgents.

| Builder Param | Type | Description |
|---------------|------|-------------|
| `llm` | `BaseChatModel` | Master Agent's LLM |
| `tools(...)` | `Tool...` | Plain tools |
| `skill(skill)` | `Skill` | Register a Skill (exposed as Tool to master) |
| `subAgent(agent)` | `SubAgent` | Register a SubAgent (exposed as Tool to master) |
| `llmFactory` | `Function<String,BaseChatModel>` | Build LLM by model name (for `model=<name>` SubAgents) |
| `context` | `FullContext` | Inject context object (used for stop/resume) |
| `onToolCall` | `Consumer<String>` | Tool call callback |
| `onObservation` | `Consumer<String>` | Tool result callback |
| `onLlm` | `Consumer<String>` | Pre-LLM-call callback |
| `maxDurationSeconds` | `int` | Max wall-clock duration in seconds; 0 = no limit |
| `maxConsecutiveToolFailures` | `int` | Max consecutive rounds where every tool call failed; 0 = no limit |
| `toolRetry` | `int` | Framework-level auto-retry count per tool call (transparent to LLM); 0 = no retry |

```java
McpAgentExecutor master = McpAgentExecutor.builder(chainActor)
    .llm(ChatAliyun.builder().model("qwen-plus").build())
    .tools(weatherTool, flightTool)
    .skill(travelSkill)
    .subAgent(researcher)
    .build();
```

### 5.3 Skill Encapsulation

**Package**: `org.salt.jlangchain.core.skill`

Packages a sub-workflow into a self-contained unit exposed to the master Agent as a plain `Tool`. Internally runs a full Function-Calling loop.

#### SkillConfig

| Field | Type | Description |
|-------|------|-------------|
| `name` | String | Skill identifier — also the Tool name |
| `description` | String | Tool description seen by the master LLM (routing basis) |
| `allowedTools` | `List<String>` | Whitelist of tool names to borrow from the parent Agent |
| `systemPrompt` | String | SKILL.md body (system prompt) |
| `maxIterations` | Integer | Max iterations for inner executor |

#### SKILL.md Directory Convention

```
skills/<name>/
  SKILL.md           ← frontmatter (name/description/allowed-tools) + system prompt
  references/        ← knowledge injection (appended to system prompt)
  scripts/           ← script tools (.py / .sh / .js auto-converted to Tool)
  agents/            ← embedded SubAgents
```

#### Usage

```java
// Load from classpath
SkillConfig config = ClasspathSkillConfigLoader.fromClasspath("skills/travel-planner");
Skill skill = Skill.from(config, chainActor)
    .llm(llm)
    .verbose(true)
    .build();

// Code-constructed
SkillConfig config = SkillConfig.builder()
    .name("weather_query")
    .description("Query city weather")
    .allowedTools(List.of("get_weather"))
    .systemPrompt("You are a weather expert...")
    .build();

// Standalone invocation
String result = skill.invoke("Check weather in Sanya");

// Register with master Agent (allowedTools borrowing handled automatically)
McpAgentExecutor master = McpAgentExecutor.builder(chainActor)
    .tools(weatherTool)
    .skill(skill)
    .build();
```

| Builder Method | Description |
|----------------|-------------|
| `Skill.from(config, chainActor)` | Create Skill Builder |
| `.llm(llm)` | Specify inner executor LLM |
| `.tools(...)` | Register tools directly (standalone mode) |
| `.verbose(true)` | Enable `[skill:<name>]` prefixed logging |
| `.onToolCall(consumer)` | Fine-grained tool call callback |
| `.onObservation(consumer)` | Fine-grained tool result callback |

### 5.4 SubAgent

**Package**: `org.salt.jlangchain.core.subagent`

Sub-agent with its own tools, exposed to the master Agent as a plain `Tool`. Core difference from Skill: SubAgent owns its tools; it doesn't depend on the parent to hold them.

#### SubAgentConfig

| Field | Type | Description |
|-------|------|-------------|
| `name` | String | SubAgent identifier — also the Tool name |
| `description` | String | Tool description seen by the master LLM |
| `model` | String | `inherit` (master's LLM) / model name / empty (explicit injection) |
| `allowedTools` | `List<String>` | Whitelist of tool names to borrow from parent Agent |
| `systemPrompt` | String | AGENT.md body |
| `maxIterations` | Integer | Max iterations for inner executor |

#### 3-Tier LLM Resolution Chain

```
Priority  Configuration                   Trigger
1         SubAgent.Builder.llm(llm)       Explicit injection — highest priority
2         model: inherit                  master build() auto-calls injectLlm(masterLlm)
3         model: qwen-plus                master build() injects llmFactory; factory runs before first invoke()
```

#### AGENT.md Format

```markdown
---
name: travel_researcher
description: Travel info expert. Use when travel information is needed.
model: inherit
tools:
  - get_weather
max-iterations: 15
---

You are a travel information expert...
```

#### Usage

```java
// Load from classpath
SubAgentConfig config = ClasspathSubAgentConfigLoader.fromClasspath("agents/travel-researcher");
SubAgent agent = SubAgent.from(config, chainActor)
    .llm(llm)
    .tools(weatherTool, flightTool)
    .verbose(true)
    .build();

// Code-constructed with llmFactory
SubAgentConfig config = SubAgentConfig.builder()
    .name("flight_checker")
    .description("Flight price specialist")
    .model("qwen-turbo")    // resolved by llmFactory
    .systemPrompt("You are a flight specialist...")
    .build();

// Register with master Agent (model=inherit / llmFactory / allowedTools auto-handled at build())
McpAgentExecutor master = McpAgentExecutor.builder(chainActor)
    .llm(masterLlm)
    .llmFactory(modelName -> ChatAliyun.builder().model(modelName).build())
    .tools(weatherTool, flightTool, hotelTool)
    .subAgent(agent)
    .build();
```

### 5.5 Agent Stop & Resume

**Package**: `org.salt.jlangchain.core.agent`

Controlled stop and progress-resume mechanism for long-running Agents — user cancellation, timeout degradation, checkpoint resume.

| API | Description |
|-----|-------------|
| `agent.stop()` | Send stop signal; Agent halts after current tool returns (never mid-tool) |
| `AgentStoppedException.getPartialContext()` | Get execution context with completed steps |
| `AgentStoppedException.getCompletedSteps()` | Get list of completed `AgentStep`s |
| `agent.invoke(question, partialCtx)` | Resume from checkpoint (skips completed steps) |
| `FullContext.build()` | Create a full context object |
| `context.createWithSteps(question, null, steps)` | Inject prior steps into a new instruction |

```java
// Async execution + stop
CompletableFuture<ChatGeneration> future = CompletableFuture.supplyAsync(
    () -> agent.invoke("Query travel info for Chengdu and Xi'an"));
toolStarted.await(10, TimeUnit.SECONDS);
agent.stop();

try {
    future.get(15, TimeUnit.SECONDS);
} catch (ExecutionException e) {
    AgentStoppedException stopped = (AgentStoppedException) e.getCause();
    // Resume from checkpoint
    ChatGeneration result = agent.invoke(question, stopped.getPartialContext());
    // Change direction with prior steps
    AgentTaskContext newCtx = context.createWithSteps(newQuestion, null, stopped.getCompletedSteps());
    ChatGeneration result2 = agent.invoke(newQuestion, newCtx);
}
```

The stop signal cascades through `ContextBus` from master Agent into SubAgents and Skill inner executors — the entire call chain halts synchronously.

### 5.6 Agent Stop Types

**Exception Hierarchy**

```
AgentException (abstract base, implements FlowControlException)
├── AgentStoppedException   External stop() call — user_cancel
├── AgentAbortException     System-forced termination (MAX_STEPS / TIMEOUT / CONSECUTIVE_TOOL_FAILURES / BUDGET_EXCEEDED)
└── AgentPauseException     Agent semantic pause (business-defined reason from upper-layer tool)
```

**AgentAbortException**

| AgentAbortReason | Trigger Condition | Builder Config |
|---|---|---|
| `MAX_STEPS` | Loop exceeded maxIterations without finishing | `.maxIterations(n)` |
| `TIMEOUT` | Execution wall time exceeded the limit | `.maxDurationSeconds(n)` |
| `CONSECUTIVE_TOOL_FAILURES` | LLM called failing tools for consecutive rounds | `.maxConsecutiveToolFailures(n)` |
| `BUDGET_EXCEEDED` | Token budget exceeded (reserved) | — |

**AgentPauseException**

Thrown by an upper-layer tool to request a semantic pause. Carries a business-defined `reason`, a `payload` map, and a `partialContext` saved by the framework for later resumption.

```java
// Inside an upper-layer tool
AgentTaskContext ctx = ContextBus.get().getTransmit(CallInfo.AGENT_TASK_CTX.name());
throw new AgentPauseException("need_approval",
    Map.of("action", "Transfer $50,000", "reason", "Exceeds auto-approval limit"), ctx);
```

**Framework Tool Retry (toolRetry)**

When a tool fails, the framework silently retries before sending an error observation back to the LLM:

```java
McpAgentExecutor agent = McpAgentExecutor.builder(chainActor)
    .toolRetry(3)                        // retry each tool call up to 3 times
    .maxConsecutiveToolFailures(5)        // abort if LLM calls failing tools 5 rounds in a row
    .maxDurationSeconds(30)              // whole task timeout: 30 seconds
    .build();
```

**Catch Example**

```java
try {
    ChatGeneration result = agent.invoke("...");
} catch (AgentStoppedException e) {
    // User cancelled; use e.getPartialContext() to resume
} catch (AgentAbortException e) {
    System.out.println("System aborted, reason: " + e.getReason()); // MAX_STEPS / TIMEOUT / ...
    // Use e.getPartialContext() to inspect completed steps
} catch (AgentPauseException e) {
    System.out.println("Agent paused, reason=" + e.getReason());
    System.out.println("payload=" + e.getPayload());
    // Handle business logic, then resume with partialContext
    ChatGeneration result = agent.invoke(question, e.getPartialContext());
}
```

### 5.7 @AgentTool / @Param / @ParamDesc / ToolScanner

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
| `AIMessage` | `core.message` | AI reply; `.getContent()` = answer, `.getReasoningContent()` = chain-of-thought (reasoning models only) |
| `HumanMessage` | `core.message` | User message |
| `AIMessageChunk` | `core.message` | Streaming AI chunk; each chunk carries `.getReasoningContent()` (reasoning delta) and `.getContent()` (answer delta); accumulated totals available after stream ends |
| `ToolMessage` | `core.message` | Tool call result |
| `EventMessageChunk` | `core.event` | Event stream chunk |
| `TtsCardChunk` | `ai.tts` | TTS audio stream chunk |
| `ChatGeneration` | `core.parser.generation` | Sync generation result (produced by `StrOutputParser`) |
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
