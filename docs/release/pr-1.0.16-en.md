# Pull Request: dev-1.0.16 → master

## Overview

This PR merges `dev-1.0.16` into `master`. Building on **1.0.15**, it focuses on Agent runtime control and observability: **Agent stop types**, **Human-in-the-Loop**, **AgentExecutor control flow enhancements**, **token usage stats & callbacks**, **execution metrics collection**, and **LLM reasoning content support** — alongside foundational additions of a unified `ConversationMemory` interface, LLM `copy()` instance replication, and Groovy script / file-system loaders.

Compared to `master`: **17** commits spanning **Feature** (control flow, observability, memory, LLM capabilities), **Fix** (LLM instance sharing), and **Docs** (Articles 26–27, Chinese & English).

---

## ✨ Key Changes

### ⏹️ Agent Stop Types

- **`AgentAbortException`**: Non-recoverable hard abort, carries `AgentAbortReason` (enum: TIMEOUT / MAX_FAILURES / USER_ABORT / TOOL_FATAL, etc.)
- **`AgentException`**: Base exception class unifying `AgentStoppedException` (recoverable) and `AgentAbortException` (non-recoverable)
- **`AgentPauseException`**: Pause exception carrying full context; supports external intervention and then resumption
- **Three stop semantics**: Stopped (signal-based, recoverable), Paused (human-in-the-loop), Aborted (hard non-recoverable)
- **Article 26 — Agent Runtime Stop Types** (Chinese & English)

### 🙋 Human-in-the-Loop

- **User confirmation function**: Agent suspends at critical waypoints by throwing `AgentPauseException`, waits for external confirmation or modification, then resumes seamlessly
- **Context preservation**: Full `AgentContext` saved on pause; resume picks up exactly where execution left off
- **Article 27 — Human-in-the-Loop** (Chinese & English)

### 🎛️ AgentExecutor Control Flow Enhancements

- **`maxDurationSeconds`**: Hard wall-clock limit on total execution; triggers `AgentAbortException(TIMEOUT)` on expiry
- **`maxConsecutiveToolFailures`**: Ceiling on back-to-back tool failures; exceeded limit triggers non-recoverable abort
- **`toolRetry`**: Per-tool automatic retry policy (attempt count + backoff)
- **Context resume**: `invoke(question, partialCtx)` supports context-aware resumption, skipping already-completed steps

### 📊 Token Usage Stats & Callbacks

- **`AiTokenUsage`**: Structured token usage (promptTokens / completionTokens / totalTokens)
- **`recordLlmUsage`**: `BaseChatModel` automatically collects token usage after streaming completes
- **`AgentTokenUsageEvent`**: Agent-level token usage event carrying round number and per-LLM-call breakdown
- **`tokenUsageConsumer`**: Register a callback on `McpAgentExecutor`; fires `onTokenUsage(event)` after each LLM call completes

### 📈 Agent Execution Metrics (AgentExecutionMetrics)

- **`AgentExecutionMetrics`**: New metrics class tracking per-round LLM call duration and tool call duration
- **`BaseAgentTaskContext`**: Context base class gains `metrics` field; `FullContext` / `SlidingWindowContext` collect metrics automatically
- **`Skill` / `SubAgent`**: Metrics collection integrated; sub-task execution data can be aggregated upward

### 🧠 Reasoning Content Support

- **`AiChatOutput.reasoningContent`**: New field for carrying chain-of-thought content from DeepSeek-R1 / o1-class models
- **`AiChatInput.thinkingConfig`**: Optional thinking configuration passed through to the underlying model
- **`BaseMessage.reasoningContent`**: Message layer supports storing and propagating reasoning content
- **`OpenAIConver`**: OpenAI protocol converter adapted for the `reasoning_content` field

### 📚 ConversationMemory Unified History Interface

- **`ConversationMemory` interface**: `storeHistory(HistoryInfos)` + `readHistory()` — unifies Storer/Reader into a single object
- **Four implementations**: `ConversationBufferMemory`, `ConversationBufferWindowMemory`, `ConversationSummaryMemory`, `ConversationSummaryBufferMemory` — each delegates to the corresponding Storer + Reader pair
- Builder pattern: appId / userId / sessionId / maxSize / storage / llm configurable per use case
- Fully compatible with the existing Flow Storer/Reader architecture — no breaking changes

### 🔄 LLM Instance Copy

- **`BaseChatModel.copy()`**: Abstract copy method returning an independent instance with the same config, used to isolate LLM state across concurrent Agent executions
- All **13** model implementations covered: Aliyun / DeepSeek / Doubao / Coze / Hunyuan / Lingyi / MiniMax / Moonshot / Ollama / OpenAI / Qianfan / StepFun / Zhipu
- **LLM instance-sharing bug fix**: `AgentExecutor` Builder's `.llm()` now calls `copy()` to prevent instance contention in multi-Agent scenarios

### 🌐 HTTP Client Timeout Configuration

- **`JLangchainConfig.callTimeout`**: Global HTTP/SSE call timeout in milliseconds
- **`HttpSseClient` / `HttpStreamClient`**: `callTimeout` propagated; both connection establishment and stream reading are uniformly bounded
- **`McpHttpConnection` / `McpSseConnection`**: MCP connection layer updated to support timeout parameter
- **`ServerConfig`**: MCP server config gains `callTimeout` field

### 🧩 Groovy Script Support + File-System Config Loaders

- **`FileSystemSkillConfigLoader`**: Load Skill configs from any file-system directory, not limited to classpath
- **`FileSystemSubAgentConfigLoader`**: Matching SubAgent file-system loader
- **`ScriptTool` Groovy support**: Script tool engine extended to support Groovy scripts as tool implementations

### ⚙️ Skill / SubAgent Batch Registration

- **`McpAgentExecutor.skills(List<Skill>)`**: Register multiple Skills in a single call
- **`McpAgentExecutor.subAgents(List<SubAgent>)`**: Register multiple SubAgents in a single call
- Replaces chained `.skill(s1).skill(s2)...` calls — cleaner batch-construction API

### 📚 Documentation & Samples

- **Article 26 — Agent Runtime Stop Types** (Chinese & English): Three stop semantics, API usage, scenario examples
- **Article 27 — Human-in-the-Loop** (Chinese & English): Pause/resume flow, confirmation function registration
- **Design docs**: `docs/design/agent-stop-and-resume.md`, `docs/design/agent-stop-and-resume-en.md` (full state machine diagrams, exception class hierarchy, resumption strategy comparison)
- **API Reference** updated: `docs/api/reference.md`, `docs/api/reference_cn.md`

---

## 📋 Environment Variables

No new environment variables in 1.0.16. Same as 1.0.15:

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

- `AgentExecutorControlTest`: Covers control flow scenarios (maxDurationSeconds / maxConsecutiveToolFailures / toolRetry / token usage callbacks)
- `Article26AgentStopTypes`: Covers all three stop semantics (Stopped / Paused / Aborted) and `AbortReason` enum values
- `ChainAgentContextTest`: Covers `AgentExecutionMetrics` collection and context propagation

---

## 📦 Version

- Release: **1.0.16** (`master` is currently **1.0.15**)
- Based on `salt-function-flow` for orchestration
- Java 17+, Spring Boot 3.2+

---

## ✅ Checklist

- [x] `pom.xml` version bumped to `1.0.16`
- [x] `ConversationMemory` interface and four implementations complete; Builder pattern verified
- [x] `AgentAbortException` / `AgentPauseException` / `AgentException` hierarchy clean and consistent
- [x] `AgentAbortReason` enum covers all abort scenarios
- [x] `maxDurationSeconds` / `maxConsecutiveToolFailures` / `toolRetry` logic verified by `AgentExecutorControlTest`
- [x] `AiTokenUsage` stats and `AgentTokenUsageEvent` callback chain end-to-end verified
- [x] `AgentExecutionMetrics` collection wired into `FullContext` / `SlidingWindowContext`
- [x] `BaseChatModel.copy()` implemented across all 13 models; LLM instance-sharing bug resolved
- [x] `AiChatOutput.reasoningContent` fully adapted through OpenAI protocol converter
- [x] `FileSystemSkillConfigLoader` / `FileSystemSubAgentConfigLoader` load from arbitrary directories
- [x] HTTP/SSE timeout propagated from `JLangchainConfig` through to MCP connection layer
- [x] Human-in-the-Loop pause/resume flow verified by Article 27 test
- [x] Chinese & English Articles 26–27 archived under `docs/article/`
- [x] Design docs archived under `docs/design/`
- [x] No breaking API changes (ConversationMemory is additive; existing Storer/Reader unchanged)
