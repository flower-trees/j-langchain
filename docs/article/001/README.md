# j-langchain 文章系列

> Java 版 LangChain，让 Java 工程师也能快速构建 AI 应用

## 文章列表

| 文章标题 | 核心内容 | 难度 | 代码示例 |
|----------|----------|------|----------|
| [5分钟用 Java 构建你的第一个 AI 应用](01-hello-ai.md) | Prompt + LLM + Parser 三步链、流式输出、JSON输出 | ⭐ | `Article01HelloAI` |
| [Java AI 应用的 5 种链式编排模式](02-chain-patterns.md) | 顺序链/条件链/组合链/并行链/路由链/动态链 | ⭐⭐ | `Article02ChainPatterns` |
| [用 Java 实现 RAG：从 PDF 加载到智能问答全流程](03-rag-pipeline.md) | 文档加载、文本切分、Embedding、Milvus、RAG问答 | ⭐⭐⭐ | `Article03RagPipeline` |
| [Java 实现 ReAct Agent：工具调用与推理循环](04-react-agent.md) | Tool定义、ReAct Prompt、推理循环、工具执行 | ⭐⭐⭐ | `Article04ReactAgent` |
| [Java AI + TTS：让大模型开口说话](05-llm-tts.md) | LLM → TTS 链路、流式语音合成、豆包/阿里云 | ⭐⭐ | `Article05LlmTts` |
| [Java AI 应用的流式输出：从原理到实战](06-streaming.md) | stream/streamEvent/stop、事件流过滤、SSE推送 | ⭐⭐ | `Article06Streaming` |
| [Java 接入多家大模型 API 实战对比](07-multi-model.md) | Ollama/阿里云/OpenAI统一接口、动态切换、降级 | ⭐ | `Article07MultiModel` |
| [在 Java AI 应用中集成 MCP 工具协议](08-mcp.md) | MCP协议、McpManager/McpClient、PostgreSQL/Memory | ⭐⭐⭐ | `Article08Mcp` |
| [AgentExecutor：用一行代码启动 ReAct Agent](09-agent-executor.md) | AgentExecutor封装、@AgentTool、Builder API、框架对比 | ⭐⭐ | `Article09AgentExecutor` |
| [多步骤 ReAct：航司比价订票实战](10-flight-compare-agent.md) | 多轮工具调用、Article10FlightTools、比价决策 | ⭐⭐ | `Article10FlightTools` / `Article09AgentExecutor` |
| [McpAgentExecutor + McpManager（HTTP API）](11-mcp-manager-agent.md) | Function Calling、HTTP MCP 工具组 | ⭐⭐⭐ | `Article11McpManagerAgent` |
| [McpAgentExecutor + McpClient（NPX 服务器）](12-mcp-client-agent.md) | filesystem 等进程型 MCP、自动选工具 | ⭐⭐⭐ | `Article12McpClientAgent` |
| [McpAgentExecutor 混合：Manager + Client](13-mcp-mixed-agent.md) | 跨 HTTP 与 NPX 的多步任务 | ⭐⭐⭐ | `Article13McpMixedAgent` |

## 建议阅读顺序

```
入门（01-hello-ai）→ 多模型（07-multi-model）→ 流式（06-streaming）→ 编排（02-chain-patterns）
→ RAG（03-rag-pipeline）→ ReAct（04-react-agent）→ AgentExecutor（09-agent-executor）
→ 航司比价（10-flight-compare-agent）→ TTS（05-llm-tts）→ MCP 基础（08-mcp）
→ McpAgentExecutor（11-mcp-manager-agent → 12-mcp-client-agent → 13-mcp-mixed-agent）
```

## 代码位置

所有文章配套代码位于：

```
src/test/java/org/salt/jlangchain/demo/article/
├── Article01HelloAI.java         ← 01-hello-ai.md
├── Article02ChainPatterns.java   ← 02-chain-patterns.md
├── Article03RagPipeline.java     ← 03-rag-pipeline.md
├── Article04ReactAgent.java      ← 04-react-agent.md
├── Article05LlmTts.java          ← 05-llm-tts.md
├── Article06Streaming.java       ← 06-streaming.md
├── Article07MultiModel.java    ← 07-multi-model.md
├── Article08Mcp.java             ← 08-mcp.md
├── Article09AgentExecutor.java   ← 09-agent-executor.md；10-flight-compare-agent.md（flightCompareAndBook）
├── Article10FlightTools.java     ← 10-flight-compare-agent.md（工具类）
├── Article11McpManagerAgent.java ← 11-mcp-manager-agent.md
├── Article12McpClientAgent.java  ← 12-mcp-client-agent.md
└── Article13McpMixedAgent.java   ← 13-mcp-mixed-agent.md
```

## 运行环境要求

| 文档 | 所需环境 |
|------|----------|
| `01-hello-ai.md`、`02-chain-patterns.md`、`06-streaming.md`、`07-multi-model.md` | Ollama 本地（免费）或 ALIYUN_KEY |
| `03-rag-pipeline.md` | Ollama + Milvus（Docker 启动） |
| `04-react-agent.md` | Ollama llama3:8b |
| `09-agent-executor.md`、`10-flight-compare-agent.md` | ALIYUN_KEY（qwen-plus） |
| `05-llm-tts.md` | Ollama + 豆包/阿里云 TTS Key |
| `08-mcp.md` | Node.js（NPX MCP 相关用例） |
| `11-mcp-manager-agent.md`、`12-mcp-client-agent.md`、`13-mcp-mixed-agent.md` | Node.js + ALIYUN_KEY（`McpAgentExecutor` + qwen3.6-plus） |
