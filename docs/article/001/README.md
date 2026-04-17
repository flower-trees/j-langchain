# j-langchain 文章系列

> Java 版 LangChain，让 Java 工程师也能快速构建 AI 应用

## 文章列表

| 文章标题                                                               | 核心内容 | 难度 | 代码示例 |
|--------------------------------------------------------------------|----------|------|----------|
| [5分钟用 Java 构建你的第一个 AI 应用](01-hello-ai.md)                          | Prompt + LLM + Parser 三步链、流式输出、JSON输出 | ⭐ | `Article01HelloAI` |
| [Java AI 应用的 5 种链式编排模式](02-chain-patterns.md)                      | 顺序链/条件链/组合链/并行链/路由链/动态链 | ⭐⭐ | `Article02ChainPatterns` |
| [用 Java 实现 RAG：从 PDF 加载到智能问答全流程](03-rag-pipeline.md)               | 文档加载、文本切分、Embedding、Milvus、RAG问答 | ⭐⭐⭐ | `Article03RagPipeline` |
| [Java 实现 ReAct Agent：工具调用与推理循环](04-react-agent.md)                 | Tool定义、ReAct Prompt、推理循环、工具执行 | ⭐⭐⭐ | `Article04ReactAgent` |
| [Java AI + TTS：让大模型开口说话](05-llm-tts.md)                            | LLM → TTS 链路、流式语音合成、豆包/阿里云 | ⭐⭐ | `Article05LlmTts` |
| [Java AI 应用的流式输出：从原理到实战](06-streaming.md)                          | stream/streamEvent/stop、事件流过滤、SSE推送 | ⭐⭐ | `Article06Streaming` |
| [Java 接入多家大模型 API 实战对比](07-multi-model.md)                         | Ollama/阿里云/OpenAI统一接口、动态切换、降级 | ⭐ | `Article07MultiModel` |
| [在 Java AI 应用中集成 MCP 工具协议](08-mcp.md)                              | MCP协议、McpManager/McpClient、PostgreSQL/Memory | ⭐⭐⭐ | `Article08Mcp` |
| [AgentExecutor：用一行代码启动 ReAct Agent](09-agent-executor.md)          | AgentExecutor封装、@AgentTool、Builder API、框架对比 | ⭐⭐ | `Article09AgentExecutor` |
| [多步骤 ReAct：航司比价订票实战](10-flight-compare-agent.md)                   | 多轮工具调用、航司比价、自动订票 | ⭐⭐ | `Article10FlightAgent` |
| [MCP Function-Calling ReAct](11-mcp-react-agent.md)                | 模型原生 ToolCall、McpManager.manifestForInput、Loop 控制 | ⭐⭐⭐ | `Article11McpReactAgent` |
| [McpAgentExecutor + McpManager（HTTP API）](12-mcp-manager-agent.md) | Function Calling、HTTP MCP 工具组 | ⭐⭐⭐ | `Article12McpManagerAgent` |
| [McpAgentExecutor + McpClient（NPX 服务器）](13-mcp-client-agent.md)    | filesystem 等进程型 MCP、自动选工具 | ⭐⭐⭐ | `Article13McpClientAgent` |
| [McpAgentExecutor 混合：Manager + Client](14-mcp-mixed-agent.md)      | 跨 HTTP 与 NPX 的多步任务 | ⭐⭐⭐ | `Article14McpMixedAgent` |
| [AgentExecutor 嵌套：旅行规划助手](15-travel-agent.md)                      | AgentExecutor 节点嵌入 chain、结构化报告 | ⭐⭐ | `Article15TravelAgent` |
| [客服双 Agent：投诉分析 + filesystem 执行](16-multi-agent-executor.md)       | ReAct + MCP 双 Agent 串联、文件写入确认 | ⭐⭐⭐ | `Article16CustomerService`（示例） |
| [国内主流厂商顺序链实例](17-domestic-vendors-chain.md)                       | 与文章2顺序链相同形态、逐个对接国内 `Chat*` | ⭐ | `Article17DomesticVendorsChain` |
| [三 Agent 并行调研：concurrent 节点构建扇出-汇聚式旅游规划助手](18-parallel-agent-concurrent.md) | concurrent 并行 Agent、cAlias、合并节点、扇出-汇聚 | ⭐⭐⭐ | `Article18ParallelTravelResearch` |
| [两行注解把企业 RPC 接口变成 AI 工具](19-rpc-vo-param.md) | @Param 标注 VO 字段、@AgentTool 包装 RPC、Dubbo / Feign 零侵入接入 | ⭐⭐ | `Article19RpcMcpTools` |

## 建议阅读顺序

```
入门（01-hello-ai）→ 多模型（07-multi-model）→ 流式（06-streaming）→ 编排（02-chain-patterns）→ 国内厂商顺序链（17-domestic-vendors-chain，可选）
→ RAG（03-rag-pipeline）→ ReAct（04-react-agent）→ AgentExecutor（09-agent-executor）
→ 航司比价（10-flight-compare-agent）→ TTS（05-llm-tts）→ MCP 基础（08-mcp）
→ MCP Function-Calling ReAct（11-mcp-react-agent）→ McpAgentExecutor（12-mcp-manager-agent → 13-mcp-client-agent → 14-mcp-mixed-agent）→ AgentExecutor 嵌套（15-travel-agent）→ 双 Agent 串联（16-multi-agent-executor）→ 并行 Agent（18-parallel-agent-concurrent）→ RPC 接入 AI 工具（19-rpc-vo-param）
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
├── Article09AgentExecutor.java   ← 09-agent-executor.md
├── Article10FlightAgent.java     ← 10-flight-compare-agent.md
├── Article11McpReactAgent.java   ← 11-mcp-react-agent.md
├── Article12McpManagerAgent.java ← 12-mcp-manager-agent.md
├── Article13McpClientAgent.java  ← 13-mcp-client-agent.md
├── Article14McpMixedAgent.java   ← 14-mcp-mixed-agent.md
├── Article15TravelAgent.java     ← 15-travel-agent.md
├── Article16CustomerService.java ← （客服双 Agent 示例）
├── Article17DomesticVendorsChain.java ← 17-domestic-vendors-chain.md
├── Article18ParallelTravelResearch.java ← 18-parallel-agent-concurrent.md
└── Article19RpcMcpTools.java            ← 19-rpc-vo-param.md
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
| `11-mcp-react-agent.md` | Node.js + ALIYUN_KEY（MCP HTTP 工具 + ReAct） |
| `12-mcp-manager-agent.md`、`13-mcp-client-agent.md`、`14-mcp-mixed-agent.md` | Node.js + ALIYUN_KEY（`McpAgentExecutor` + qwen3.6-plus） |
| `Article16CustomerService`（示例） | Node.js + ALIYUN_KEY（ReAct + filesystem MCP） |
| `17-domestic-vendors-chain.md` / `Article17DomesticVendorsChain` | 按需配置对应厂商 API Key（见文内表格）；Coze 另需有效 `COZE_BOT_ID` |
| `18-parallel-agent-concurrent.md` / `Article18ParallelTravelResearch` | `ALIYUN_KEY`（`qwen-plus`） |
| `19-rpc-vo-param.md` / `Article19RpcMcpTools` | `ALIYUN_KEY`（`qwen-plus`） |
