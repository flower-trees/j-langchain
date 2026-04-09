# Pull Request: dev-1.0.13 → master

## Overview

This PR merges `dev-1.0.13` into `master`. On top of **1.0.12**, it expands multi-vendor LLM support, adds **AgentExecutor** / **McpAgentExecutor** and **@AgentTool**-based tool scanning, completes the MCP Function Calling path, and ships a full set of tutorials and sample code. It also fixes several agent issues: observation truncation, Final Answer extraction, and MCP iteration / tool-call handling.

Compared to `master`, there are **15** commits spanning **Feature** (multi-vendor LLMs, `AgentExecutor`, `McpAgentExecutor`, `@AgentTool`, MCP docs and samples), **Fix** (MCP messages and iteration limits, observation truncation), and **Refactor** (executor inheritance, Final Answer flow, documentation layout). `git diff master..HEAD`: **61** files changed, **5574** insertions, **232** deletions.

---

## ✨ Key Changes

### 🤖 Agent & MCP Executors

- **AgentExecutor**: Wraps the ReAct loop and simplifies building and invoking agents
- **McpAgentExecutor**: Model-side **Function Calling** with MCP tool orchestration
- **Inheritance & extraction**: Executors extend the underlying runnable type; improved Final Answer extraction and tool argument handling
- **Fixes**: MCP agent tool-call messages, iteration limits, observation truncation from model hallucination

### 🛠️ Tool Definition & Scanning

- **@AgentTool / @Param**: Declarative multi-parameter tool definitions
- **ToolScanner**: Discovers and registers annotated tool methods
- **McpManager**: Enhancements (including manifest integration for agent inputs; see code and articles 11–14)

### 🤖 LLM Vendors

New chat model wrappers with **Actuator** beans registered in `JLangchainConfig`, plus unit tests:

- **DeepSeek** — `ChatDeepseek`
- **Tencent Hunyuan** — `ChatHunyuan`
- **Baidu Qianfan** — `ChatQianfan`
- **Zhipu GLM** — `ChatZhipu`
- **MiniMax** — `ChatMinimax`
- **01.AI Yi** — `ChatLingyi`
- **StepFun Step** — `ChatStepfun`

### 📚 Documentation & Samples

- **Article series** (`docs/article/001/`): Reworked **08-mcp**; added **09–16** (AgentExecutor, flight price comparison, MCP ReAct / Manager / Client / hybrid, nested travel planning, dual-agent customer service); updated **001/README.md** (index, reading order, code paths, environment matrix)
- **Sample classes** (`demo/article/`): Extended `Article08Mcp`; added `Article09`–`Article16` with self-contained inner tool classes for doc-aligned runs
- **README / README_CN**: Seven new vendors, parallel-chain example using `concurrent` + `ctx.getResult(flowId)`, comparison table “multi-vendor” count

### 🔗 Chain Examples

- **Parallel execution**: README joke/poem example now uses `concurrent` + `ctx.getResult(flowId)` (aligned with `Article02ChainPatterns`, `ChainDemoTest`)

---

## 📋 Environment Variables

Building on the 1.0.11 set, new vendor keys (or use `models.<vendor>.chat-key` in config):

| Variable | Description |
|----------|-------------|
| CHATGPT_KEY | OpenAI |
| ALIYUN_KEY | Alibaba Cloud Qwen |
| MOONSHOT_KEY | Moonshot (Kimi) |
| DOUBAO_KEY | Doubao |
| COZE_KEY | Coze (or OAuth) |
| OLLAMA_KEY1 | Ollama |
| ALIYUN_TTS_KEY | Alibaba Cloud TTS |
| DOUBAO_TTS_KEY | Doubao TTS |
| DEEPSEEK_KEY | DeepSeek |
| HUNYUAN_KEY | Tencent Hunyuan |
| QIANFAN_KEY | Baidu Qianfan |
| ZHIPU_KEY | Zhipu AI |
| MINIMAX_KEY | MiniMax |
| LINGYI_KEY | 01.AI |
| STEPFUN_KEY | StepFun |

---

## 🧪 Testing

- Added `Chat*Test` samples for the new vendors
- Added/updated `Article02`, `Article04`, `Article08`–`Article16` demos and `ChainDemoTest`
- Updated test `mcp.config.json`, `logback.xml`, and related config

---

## 📦 Version

- Release: **1.0.13** (`master` is currently **1.0.12**)
- Based on `salt-function-flow` for orchestration
- Java 17+, Spring Boot 3.2+

---

## ✅ Checklist

- [x] Docs and article index updated (MCP / AgentExecutor series)
- [x] Chinese and English README and capability blurbs in sync
- [x] New samples and tests cover new models and agent paths
- [x] Environment variables documented in the table above
- [x] Breaking changes reviewed (README parallel-chain example reflects API usage update)
