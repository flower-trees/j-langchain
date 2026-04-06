# j-langchain 文章系列

> Java 版 LangChain，让 Java 工程师也能快速构建 AI 应用

## 文章列表

| # | 文章标题 | 核心内容 | 难度 | 代码示例 |
|---|----------|----------|------|----------|
| 1 | [5分钟用 Java 构建你的第一个 AI 应用](01-hello-ai.md) | Prompt + LLM + Parser 三步链、流式输出、JSON输出 | ⭐ | `Article01HelloAI` |
| 2 | [Java AI 应用的 5 种链式编排模式](02-chain-patterns.md) | 顺序链/条件链/组合链/并行链/路由链/动态链 | ⭐⭐ | `Article02ChainPatterns` |
| 3 | [用 Java 实现 RAG：从 PDF 加载到智能问答全流程](03-rag-pipeline.md) | 文档加载、文本切分、Embedding、Milvus、RAG问答 | ⭐⭐⭐ | `Article03RagPipeline` |
| 4 | [Java 实现 ReAct Agent：工具调用与推理循环](04-react-agent.md) | Tool定义、ReAct Prompt、推理循环、工具执行 | ⭐⭐⭐ | `Article04ReactAgent` |
| 4b | [AgentExecutor：用一行代码启动 ReAct Agent](04b-agent-executor.md) | AgentExecutor封装、Builder API、框架对比 | ⭐⭐ | `Article04ReactAgent` |
| 5 | [Java AI + TTS：让大模型开口说话](05-llm-tts.md) | LLM → TTS 链路、流式语音合成、豆包/阿里云 | ⭐⭐ | `Article05LlmTts` |
| 6 | [Java AI 应用的流式输出：从原理到实战](06-streaming.md) | stream/streamEvent/stop、事件流过滤、SSE推送 | ⭐⭐ | `Article06Streaming` |
| 7 | [Java 接入多家大模型 API 实战对比](07-multi-model.md) | Ollama/阿里云/OpenAI统一接口、动态切换、降级 | ⭐ | `Article07MultiModel` |
| 8 | [在 Java AI 应用中集成 MCP 工具协议](08-mcp.md) | MCP协议、McpManager/McpClient、PostgreSQL/Memory | ⭐⭐⭐ | `Article08Mcp` |

## 建议阅读顺序

```
文章1（入门）→ 文章7（多模型）→ 文章6（流式）→ 文章2（编排）→ 文章3（RAG）→ 文章4（Agent）→ 文章4b（AgentExecutor）→ 文章5（TTS）→ 文章8（MCP）
```

## 代码位置

所有文章配套代码位于：

```
src/test/java/org/salt/jlangchain/demo/article/
├── Article01HelloAI.java       ← 文章1
├── Article02ChainPatterns.java ← 文章2
├── Article03RagPipeline.java   ← 文章3
├── Article04ReactAgent.java    ← 文章4、4b
├── Article05LlmTts.java        ← 文章5
├── Article06Streaming.java     ← 文章6
├── Article07MultiModel.java    ← 文章7
└── Article08Mcp.java           ← 文章8
```

## 运行环境要求

| 文章 | 所需环境 |
|------|----------|
| 1, 2, 6, 7 | Ollama 本地（免费）或 ALIYUN_KEY |
| 3 | Ollama + Milvus（Docker 启动） |
| 4 | Ollama llama3:8b |
| 4b | ALIYUN_KEY（qwen-plus） |
| 5 | Ollama + 豆包/阿里云 TTS Key |
| 8 | Node.js（用于 NPX MCP 服务器） |
