# Pull Request: dev-1.0.15 → master

## Overview

This PR merges `dev-1.0.15` into `master`. Building on **1.0.14**, it introduces three core capabilities for multi-agent collaboration: **Skill encapsulation**, **SubAgent autonomous sub-agents**, and **Agent stop & resume** — along with `McpAgentExecutor` tool-call display improvements. Together these form j-langchain's layered Agent architecture, turning complex multi-agent system construction from manual orchestration into standardized encapsulation.

Compared to `master`: **5** commits spanning **Feature** (Skill, SubAgent), **Refactor** (Agent stop exceptions, tool invocation handling), and **Docs** (Articles 20–24, Chinese & English).

---

## ✨ Key Changes

### 🎯 Skill Encapsulation (Skill Agent)

- **`Skill` class**: Standard encapsulation unit for sub-workflows. Exposed to the master Agent as a plain `Tool`; internally runs an independent `McpAgentExecutor` loop
- **`SkillConfig`**: Pure data config object — classpath loading, database reads, and code construction are all equivalent
- **`ClasspathSkillConfigLoader`**: Parses the `skills/<name>/SKILL.md` directory convention; auto-loads `references/` (knowledge injection), `scripts/` (script tools), and `agents/` (embedded sub-agents)
- **3-tier tool sourcing**: Script tools (`scripts/`) → explicitly registered (`.tools()`) → `allowedTools` borrowed from parent — three layers merged without interference
- **3 runtime modes**: classpath + Master (tool borrowing), code-constructed (no file dependency), standalone (`skill.invoke()` directly)
- **`verbose` + fine-grained callbacks**: `[skill:<name>]` prefixed logs; `onLlm` / `onToolCall` / `onObservation` three-level callback hooks

### 🤖 SubAgent (Autonomous Sub-Agent)

- **`SubAgent` class**: Sub-agent encapsulation with its own tools. Exposed as a plain `Tool` to the master Agent — same interface as Skill, freely mixable
- **`SubAgentConfig`**: Pure data config object supporting `AGENT.md` classpath loading and code construction
- **`ClasspathSubAgentConfigLoader`**: Parses `agents/<name>/AGENT.md`; supports `skills` field for static Skill knowledge injection into system prompt
- **3-tier LLM resolution chain**: Explicit injection (`.llm()`) > `model: inherit` (inherits master's LLM) > `model: <name>` + `llmFactory` (factory-by-name)
- **`allowedTools` whitelist**: SubAgent borrows declared tools from parent Agent under least-privilege principle; master `build()` auto-filters and injects
- **Skill embedding SubAgent**: `skills/<name>/agents/*.md` auto-parsed as `SubAgentConfig` and registered as tools in Skill's inner executor; log prefix upgrades to two-level `[skill:<s>><a>]`
- **Lazy initialization**: `injectLlm` / `injectLlmFactory` / `injectParentTools` can be called in any order; executor is built before first `invoke()`

### ⏸️ Agent Stop & Resume

- **`agent.stop()`**: Sends stop signal; Agent halts at the next safe checkpoint after the current tool returns — guaranteeing tool-call atomicity
- **`AgentStoppedException`**: Carries `partialContext` (list of completed `AgentStep`s) — interrupt means save progress
- **Signal cascading**: Stop signal propagates through `ContextBus` from master Agent into SubAgents and Skill inner executors — entire call chain halts synchronously
- **`agent.invoke(question, partialCtx)`**: Resume from checkpoint, skipping completed steps
- **`FullContext.createWithSteps()`**: Inject prior steps into a new instruction — "change direction while keeping your progress"
- **3 resumption strategies**: Restart from scratch / resume from checkpoint / prior steps + new instruction

### 🔧 McpAgentExecutor Tool Call Display

- Updated message formatting logic; tool call logs are cleaner and `onToolCall` callback output is standardized

### 📚 Documentation & Samples

- **Article 20 — Dual-Agent Self-Correcting Code Generation** (Chinese & English)
- **Article 21 — Proposer-Critic Multi-Round Debate** (Chinese & English)
- **Article 22 — Skill Agent** (Chinese & English)
- **Article 23a — SubAgent Basics** (Chinese & English)
- **Article 23b — SubAgent Advanced: LLM Strategies, Tool Borrowing, Skill Nesting** (Chinese & English)
- **Article 24 — Agent Stop & Resume** (Chinese & English)
- **Design docs**: `docs/design/skill.md`, `docs/design/subagent.md` (full architecture diagrams, data structures, usage examples)

---

## 📋 Environment Variables

No new environment variables in 1.0.15. Same as 1.0.14:

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

- `Article22SkillAgent`: Covers 3 Skill runtime modes (classpath + Master / code-constructed / standalone)
- `Article23SubAgent`: Covers 7 SubAgent scenarios (standalone / Master / code-constructed / inherit / llmFactory / allowedTools / embedded SubAgent)
- `Article24StopAndResume`: Covers 5 stop & resume scenarios (basic stop / partialContext / SubAgent signal propagation / resume from checkpoint / new instruction with prior steps)

---

## 📦 Version

- Release: **1.0.15** (`master` is currently **1.0.14**)
- Based on `salt-function-flow` for orchestration
- Java 17+, Spring Boot 3.2+

---

## ✅ Checklist

- [x] `pom.xml` version bumped to `1.0.15`
- [x] `Skill` / `SkillConfig` / `ClasspathSkillConfigLoader` fully implemented
- [x] `SubAgent` / `SubAgentConfig` / `ClasspathSubAgentConfigLoader` fully implemented
- [x] 3-tier LLM resolution chain (explicit / inherit / llmFactory) behavior verified
- [x] `allowedTools` whitelist injection validated through master `build()`
- [x] `stop()` signal cascades correctly through Master → SubAgent → Skill chain
- [x] `AgentStoppedException.getPartialContext()` carries completed steps
- [x] `FullContext.createWithSteps()` new-instruction-with-prior-steps verified
- [x] Chinese & English Articles 20–24 added under `docs/article/`
- [x] Design docs `skill.md` and `subagent.md` archived under `docs/design/`
- [x] No breaking API changes (Skill / SubAgent both expose `Tool` interface, transparent to master Agent)
