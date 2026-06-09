# Pull Request: dev-1.0.17 → master

## Overview

This PR merges `dev-1.0.17` into `master`. Building on **1.0.16**, it closes the observability loop with **Skill / SubAgent token-usage callbacks** (`onTokenUsage`) that propagate `AgentTokenUsageEvent` directly, alongside a fix for the **duplicate `temperature` field** in OpenAI-compatible requests, and an updated documentation index (29 EN / 31 CN tutorials).

2 commits relative to `master`: **Feature** (token callback propagation), **Fix** (OpenAI request parameter conflict), **Docs** (tutorial index update).

---

## ✨ Key Changes

### 📊 Skill / SubAgent Token-Usage Callback (onTokenUsage)

- **`Skill.Builder.onTokenUsage(Consumer<AgentTokenUsageEvent>)`**: New callback fired after each LLM call inside a Skill; the callback type is the same `AgentTokenUsageEvent` used by the parent agent's `tokenUsageConsumer`
- **`SubAgent.Builder.onTokenUsage(Consumer<AgentTokenUsageEvent>)`**: Same addition for SubAgent, enabling per-sub-agent token reporting
- **Propagation**: `Skill` / `SubAgent` inject `onTokenUsage` into the internal `McpAgentExecutor` during `buildExecutor()`; the callback chain is complete end-to-end
- Fully compatible with the 1.0.16 `AiTokenUsage` / `AgentTokenUsageEvent` structures — no breaking changes

### 🔧 Fix OpenAI Request Parameter Conflict (duplicate temperature field)

- **Root cause**: When `AiChatInput.temperature` is set via its dedicated setter *and* `extraParams` also contains `"temperature"`, `@JsonAnyGetter` serialises both, producing a duplicate JSON field that causes HTTP 400 on DeepSeek / OpenAI-compatible endpoints
- **Fix**: `OpenAIConver.convertRequest()` removes `"temperature"` from `extraParams` before writing `extraFields`; the extracted value overrides the dedicated-setter value, ensuring a single canonical field
- Other standard fields (`top_p`, `max_tokens`, etc.) in `extraParams` similarly do not duplicate — their dedicated setters already cover them

### 📚 Documentation Updates

- **README (EN)**: Tutorial count increased from 24 to 29; added standalone entries for Function-Calling ReAct, Manager Agent, Client Agent, Hybrid MCP Agent, Agent stop types, Human-in-the-Loop, observability, and reasoning content
- **README_CN (ZH)**: Tutorial count increased from 24 to 31; added domestic-vendor sequential chain interface, PDF document Q&A Agent, Agent stop types, Human-in-the-Loop, and more
- MCP-related tutorials split into individual entries for clarity

---

## 📋 Environment Variables

No new environment variables in 1.0.17. Same as 1.0.16:

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

- `Skill` / `SubAgent` `onTokenUsage` callback: verified `AgentTokenUsageEvent` fires correctly through the execution chain
- `OpenAIConver` fix: verified that `extraParams` containing `"temperature"` no longer produces a duplicate field; DeepSeek extended-thinking scenario passes

---

## 📦 Version

- Release: **1.0.17** (`master` is currently **1.0.16**)
- Built on `salt-function-flow` orchestration
- Java 17+, Spring Boot 3.2+

---

## ✅ Checklist

- [x] `pom.xml` version bumped to `1.0.17`
- [x] `docs/guide/quickstart.md` / `quickstart_cn.md` dependency version updated to `1.0.17`
- [x] `Skill.Builder.onTokenUsage` fully implemented and injected in `buildExecutor()`
- [x] `SubAgent.Builder.onTokenUsage` fully implemented and injected in `buildExecutor()`
- [x] `OpenAIConver.convertRequest()` duplicate `temperature` field fixed; HTTP 400 eliminated
- [x] README / README_CN tutorial index counts and entries updated
- [x] No breaking API changes (new Builder methods; existing callers unaffected)
