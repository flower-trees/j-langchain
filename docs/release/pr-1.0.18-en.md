# Pull Request: dev-1.0.18 → master

## Overview

This PR merges `dev-1.0.18` into `master`. Building on **1.0.17**, it adds two execution-control capabilities: **SubAgent private tool configuration** (`SubAgentConfig.ownTools`, a standard data field for declaring tools visible only to that sub-agent) and **McpAgentExecutor last-tool-result passthrough** (`returnLastToolResult`, skipping the LLM's rewrite pass and returning the tool observation directly once it already satisfies the goal).

2 commits relative to `master`, both **Feature** (SubAgent private tool field, last-tool-result passthrough).

---

## ✨ Key Changes

### 🔒 SubAgent Private Tool Configuration (SubAgentConfig.ownTools)

- **`SubAgentConfig.ownTools`**: new `List<Tool>` field for declaring tools that belong only to that sub-agent's internal executor
- **Difference from `allowedTools`**: `allowedTools` borrows the parent agent's already-registered shared tools by name (`List<String>`, injected at runtime via `injectParentTools`); `ownTools` holds actual `Tool` objects privately owned by the sub-agent (`List<Tool>`) and is never exposed as a capability in the parent's marketplace
- This is a data-layer addition: callers building an executor via `SubAgent.from(config, chainActor)` still need to explicitly inject private tools with `.tools(config.getOwnTools())` (an existing `SubAgent.Builder` method) — this lets higher-level frameworks read private-tool definitions from config/annotations and wire them in by convention
- Adds a dependency on `org.salt.jlangchain.rag.tools.Tool`; field Javadoc documents the scope of private tools

### ⚡ McpAgentExecutor Last-Tool-Result Passthrough (returnLastToolResult)

- **`McpAgentExecutor.Builder.returnLastToolResult(boolean)`**: new config option — when a tool call has already produced an observation that satisfies the user's goal, skip the LLM's rewrite pass and return that observation directly as the final answer
- **Implementation**: a new `LAST_TOOL_RESULT` context-store key is written to `ContextBus` after every tool observation; a new `TranslateHandler` stage in the pipeline substitutes the stored last-tool-result for the LLM-generated content whenever `returnLastToolResult=true` and the current output is a `ChatGeneration`
- **System prompt coupling**: enabling this option automatically appends a rule to `systemPrompt` instructing the model to stop calling tools and finish once a result satisfies the goal, keeping the final response minimal (success/failure only, no further elaboration)
- Adds a dedicated `McpAgentExecutorReturnLastToolResultTest` to verify the behavior

---

## 📋 Environment Variables

No new environment variables in 1.0.18. Same as 1.0.17:

| Variable | Provider |
|----------|----------|
| CHATGPT_KEY | OpenAI |
| ALIYUN_KEY | Alibaba Qwen |
| MOONSHOT_KEY | Moonshot (Kimi) |
| DOUBAO_KEY | Doubao |
| COZE_KEY | Coze (or OAuth) |
| OLLAMA_KEY1 | Ollama |
| ALIYUN_TTS_KEY | Alibaba TTS |
| DOUBAO_TTS_KEY | Doubao TTS |
| DEEPSEEK_KEY | DeepSeek |
| HUNYUAN_KEY | Tencent Hunyuan |
| QIANFAN_KEY | Baidu Qianfan |
| ZHIPU_KEY | Zhipu AI |
| MINIMAX_KEY | MiniMax |
| LINGYI_KEY | LingYi (01.AI) |
| STEPFUN_KEY | StepFun |

---

## 🧪 Testing

- `McpAgentExecutorReturnLastToolResultTest`: verified that with `returnLastToolResult(true)`, the tool's observation is returned directly as the final `ChatGeneration` without an LLM rewrite pass
- `SubAgentConfig.ownTools`: verified field read/write and the manual injection path via `SubAgent.Builder.tools(...)`

---

## 📦 Version

- Release: **1.0.18** (`master` is currently **1.0.17**)
- Built on `salt-function-flow` orchestration
- Java 17+, Spring Boot 3.2+

---

## ✅ Checklist

- [ ] `pom.xml` version bumped from `1.0.18-SNAPSHOT` to `1.0.18`
- [ ] `docs/guide/quickstart.md` / `quickstart_cn.md` dependency version updated to `1.0.18`
- [x] `SubAgentConfig.ownTools` field complete, Javadoc clearly distinguishes it from `allowedTools`
- [x] `McpAgentExecutor.Builder.returnLastToolResult` fully implemented; `LAST_TOOL_RESULT` propagation and `TranslateHandler` substitution verified
- [x] System-prompt rule appended correctly based on the `returnLastToolResult` flag
- [x] `McpAgentExecutorReturnLastToolResultTest` passes
- [x] No breaking API changes (new field / new Builder method only; default values preserve existing behavior)
