# j-langchain Article Series

> LangChain for Java: build AI applications quickly as a Java engineer

## Article List

| Title                                                              | Key Topics | Difficulty | Code Sample |
|--------------------------------------------------------------------|------------|------------|-------------|
| [Build Your First AI App in Java in 5 Minutes](01-hello-ai.md)     | Prompt + LLM + Parser three-step chain, streaming output, JSON output | ⭐ | `Article01HelloAI` |
| [Five Chain Orchestration Patterns for Java AI Apps](02-chain-patterns.md) | Sequential/conditional/composed/parallel/router/dynamic chains | ⭐⭐ | `Article02ChainPatterns` |
| [Implementing RAG in Java: From PDF Loading to Q&A](03-rag-pipeline.md) | Document loading, text splitting, embeddings, Milvus, RAG Q&A | ⭐⭐⭐ | `Article03RagPipeline` |
| [ReAct Agent in Java: Tool Calls and Reasoning Loop](04-react-agent.md) | Tool definitions, ReAct prompt, reasoning loop, tool execution | ⭐⭐⭐ | `Article04ReactAgent` |
| [Java AI + TTS: Make the LLM Speak](05-llm-tts.md)                 | LLM → TTS pipeline, streaming synthesis, Doubao/Aliyun | ⭐⭐ | `Article05LlmTts` |
| [Streaming Output in Java AI Apps: Theory to Practice](06-streaming.md) | stream/streamEvent/stop, event filtering, SSE push | ⭐⭐ | `Article06Streaming` |
| [Integrating Multiple LLM Vendors in Java](07-multi-model.md)       | Unified interface for Ollama/Aliyun/OpenAI, dynamic switching, fallback | ⭐ | `Article07MultiModel` |
| [Integrate the MCP Tool Protocol in Java](08-mcp.md)                | MCP protocol, McpManager/McpClient, PostgreSQL/Memory | ⭐⭐⭐ | `Article08Mcp` |
| [AgentExecutor: Start a ReAct Agent with One Line](09-agent-executor.md) | AgentExecutor wrapper, @AgentTool, builder API, framework comparison | ⭐⭐ | `Article09AgentExecutor` |
| [Multi-step ReAct: Airline Price Comparison](10-flight-compare-agent.md) | Multi-step tool calls, airline comparison, auto booking | ⭐⭐ | `Article10FlightAgent` |
| [MCP Function-Calling ReAct](11-mcp-react-agent.md)                 | Native ToolCall, McpManager.manifestForInput, loop control | ⭐⭐⭐ | `Article11McpReactAgent` |
| [McpAgentExecutor + McpManager (HTTP API)](12-mcp-manager-agent.md) | Function Calling, HTTP MCP tool groups | ⭐⭐⭐ | `Article12McpManagerAgent` |
| [McpAgentExecutor + McpClient (NPX Server)](13-mcp-client-agent.md) | Process-based MCP such as filesystem, auto tool selection | ⭐⭐⭐ | `Article13McpClientAgent` |
| [Hybrid McpAgentExecutor: Manager + Client](14-mcp-mixed-agent.md)  | Multi-step tasks across HTTP and NPX | ⭐⭐⭐ | `Article14McpMixedAgent` |
| [Nested AgentExecutor: Travel Planner](15-agent-executor-embed.md)  | Embed AgentExecutor nodes into a chain, structured report | ⭐⭐ | `Article15TravelAgent` |
| [Dual Agents for Customer Support](16-multi-agent-executor.md)      | ReAct + MCP agents in tandem, filesystem write confirmation | ⭐⭐⭐ | `Article16CustomerService` (sample) |
| [Sequential Chains for Domestic Vendors](17-domestic-vendors-chain.md) | Same as Article 2 sequential chain, connect each domestic `Chat*` API | ⭐ | `Article17DomesticVendorsChain` |
| [Parallel Agent Research: Fan-out / Fan-in with `concurrent`](18-parallel-agent-concurrent.md) | Concurrent AgentExecutor nodes, `cAlias`, merge lambda, fan-out / fan-in | ⭐⭐⭐ | `Article18ParallelTravelResearch` |
| [Turn Enterprise RPC into AI Tools with Two Annotations](19-rpc-vo-param.md) | `@Param` on VO fields, `@AgentTool` wrapping RPC, zero-intrusion Dubbo / Feign wiring | ⭐⭐ | `Article19RpcMcpTools` |

## Suggested Reading Order

```
Getting started (01-hello-ai) → Multi-model (07-multi-model) → Streaming (06-streaming) → Orchestration (02-chain-patterns) → Domestic vendors sequential chain (17-domestic-vendors-chain, optional)
→ RAG (03-rag-pipeline) → ReAct (04-react-agent) → AgentExecutor (09-agent-executor)
→ Airline comparison (10-flight-compare-agent) → TTS (05-llm-tts) → MCP basics (08-mcp)
→ MCP Function-Calling ReAct (11-mcp-react-agent) → McpAgentExecutor (12-mcp-manager-agent → 13-mcp-client-agent → 14-mcp-mixed-agent) → Nested AgentExecutor (15-travel-agent) → Dual agents (16-multi-agent-executor) → Parallel agents (18-parallel-agent-concurrent) → RPC as AI tools (19-rpc-vo-param)
```

## Code Location

All companion code lives at:

```
src/test/java/org/salt/jlangchain/demo/article/
├── Article01HelloAI.java         ← 01-hello-ai.md
├── Article02ChainPatterns.java   ← 02-chain-patterns.md
├── Article03RagPipeline.java     ← 03-rag-pipeline.md
├── Article04ReactAgent.java      ← 04-react-agent.md
├── Article05LlmTts.java          ← 05-llm-tts.md
├── Article06Streaming.java       ← 06-streaming.md
├── Article07MultiModel.java      ← 07-multi-model.md
├── Article08Mcp.java             ← 08-mcp.md
├── Article09AgentExecutor.java   ← 09-agent-executor.md
├── Article10FlightAgent.java     ← 10-flight-compare-agent.md
├── Article11McpReactAgent.java   ← 11-mcp-react-agent.md
├── Article12McpManagerAgent.java ← 12-mcp-manager-agent.md
├── Article13McpClientAgent.java  ← 13-mcp-client-agent.md
├── Article14McpMixedAgent.java   ← 14-mcp-mixed-agent.md
├── Article15TravelAgent.java     ← 15-travel-agent.md
├── Article16CustomerService.java ← (customer-support dual agent sample)
├── Article17DomesticVendorsChain.java ← 17-domestic-vendors-chain.md
├── Article18ParallelTravelResearch.java ← 18-parallel-agent-concurrent.md
└── Article19RpcMcpTools.java            ← 19-rpc-vo-param.md
```

## Runtime Requirements

| Document | Needed Environment |
|----------|--------------------|
| `01-hello-ai.md`, `02-chain-patterns.md`, `06-streaming.md`, `07-multi-model.md` | Local Ollama (free) or `ALIYUN_KEY` |
| `03-rag-pipeline.md` | Ollama + Milvus (Docker) |
| `04-react-agent.md` | Ollama `llama3:8b` |
| `09-agent-executor.md`, `10-flight-compare-agent.md` | `ALIYUN_KEY` (`qwen-plus`) |
| `05-llm-tts.md` | Ollama + Doubao/Aliyun TTS key |
| `08-mcp.md` | Node.js (NPX MCP samples) |
| `11-mcp-react-agent.md` | Node.js + `ALIYUN_KEY` (MCP HTTP tools + ReAct) |
| `12-mcp-manager-agent.md`, `13-mcp-client-agent.md`, `14-mcp-mixed-agent.md` | Node.js + `ALIYUN_KEY` (`McpAgentExecutor` + `qwen3.6-plus`) |
| `Article16CustomerService` (sample) | Node.js + `ALIYUN_KEY` (ReAct + filesystem MCP) |
| `17-domestic-vendors-chain.md` / `Article17DomesticVendorsChain` | Configure the required vendor API keys as needed (see table in the article); Coze also requires a valid `COZE_BOT_ID` |
| `18-parallel-agent-concurrent.md` / `Article18ParallelTravelResearch` | `ALIYUN_KEY` (`qwen-plus`) |
| `19-rpc-vo-param.md` / `Article19RpcMcpTools` | `ALIYUN_KEY` (`qwen-plus`) |
