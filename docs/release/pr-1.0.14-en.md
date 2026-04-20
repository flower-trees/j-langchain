# Pull Request: dev-1.0.14 → master

## Overview

This PR merges `dev-1.0.14` into `master`. Building on **1.0.13**, it focuses on strengthening tool parameter description capabilities and multi-application history management support. Key highlights: **complex object type tool parameters**, **@ParamDesc multi-level priority parameter descriptions**, **History multi-application + thread-safe refactor**, Agent executor callback Consumer-mode migration, and two new tutorials covering parallel Agent orchestration and zero-intrusion RPC AI tool integration.

Compared to `master`, there are **11** commits spanning **Feature** (complex object parameters, multi-level parameter descriptions), **Refactor** (history management, agent callbacks, builder pattern, message classes), and **Docs** (Article 18, 19). `git diff master..HEAD`: **67** files changed, **5575** insertions, **374** deletions.

---

## ✨ Key Changes

### 🛠️ Tool Parameter Enhancement

- **Complex Object Type Parameters** (`Feature: rag`)
  - Extended `@Param` annotation to support `ElementType.FIELD` targets, enabling parameter description on object fields
  - Added `isComplexType` method to identify types requiring JSON deserialization
  - Introduced `ObjectSchema` record class to auto-generate schema descriptions and JSON examples for complex types
  - Integrated Jackson `ObjectMapper` for deserializing Action Input JSON strings into complex objects before method invocation
  - Complete unit tests covering single-string, multi-parameter, and complex-object parameter scenarios

- **Multi-Level Priority Parameter Descriptions `@ParamDesc`** (`Feature: annotation`)
  - New `@ParamDesc` annotation and `AgentTool.params` attribute for describing third-party object parameters
  - Parameter description priority: `@AgentTool.params` > VO field `@Param` > method parameter `@Param`
  - Spring AOP integration to penetrate proxy classes and retrieve real annotation metadata
  - Best-practice support for **zero-intrusion** AI tool exposure of enterprise RPC interfaces (Dubbo / Feign)

### 🔧 Tool Scanner

- **Parameter Processing Optimization** (`Refactor: tool`): Refactored `ToolScanner` parameter scanning pipeline for improved accuracy and maintainability

### 📜 History Management Refactor

- **Multi-Application + Thread Safety** (`Refactor: history`)
  - `HistoryBase` gains `appId`, `userId`, and `sessionId` fields
  - `MemoryHistory` storage upgraded from flat `Map` to a three-layer nested `ConcurrentHashMap` (`appId → userId → sessionId`), providing natural multi-application isolation
  - Replaced `init` method with lazy `getOrCreate` pattern
  - `MemoryHistoryStorer` adds `maxSize` for capping history length
  - Synchronized read/write operations to guarantee concurrency safety

### 🤖 Agent Executor

- **Callback Consumer Mode** (`Refactor: agent`)
  - `ThoughtLogger`, `ObservationLogger`, `ToolCallLogger` renamed to corresponding Consumer form
  - New `onLlm` callback hook captures complete input before model invocation
  - `AgentExecutor` inserts `emitBeforeLlm` handler to enable LLM input listening
  - `McpAgentExecutor` adds Chat Prompt Value formatting support

### 🏗️ Core Refactor

- **Manual Builder** (`Refactor: common`): Replaced Lombok `@Builder` with manual builder implementations for finer control
- **EqualsAndHashCode** (`Refactor: core`): Added `@EqualsAndHashCode` to message and loader classes
- **Field Initialization** (`Refactor: core`): Moved class-level field initialization into constructors, aligning with best practices

### 📚 Documentation & Samples

- **Article 18 — Parallel Agent Concurrent Research** (Chinese & English)
  - Scenario: Multiple agents gather information concurrently then aggregate results, demonstrating `concurrent` + `ctx.getResult(flowId)` usage
  - Sample class: `Article18ParallelTravelResearch`

- **Article 19 — Zero-Intrusion RPC AI Tool Integration** (Chinese & English, generalized from the original Dubbo-specific article)
  - Scenario: RPC interfaces (Dubbo / Feign) exposed as AI tools with zero code changes via `@ParamDesc` + Spring AOP proxy
  - Sample class: `Article19RpcMcpTools`

---

## 📋 Environment Variables

No new environment variables introduced in 1.0.14. Same as 1.0.13:

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

- New `ToolScannerTest` covers three scenarios: single-string parameter, multi-parameter, and complex-object parameter
- New `Article18ParallelTravelResearch` and `Article19RpcMcpTools` demo classes

---

## 📦 Version

- Release: **1.0.14** (`master` is currently **1.0.13**)
- Based on `salt-function-flow` for orchestration
- Java 17+, Spring Boot 3.2+

---

## ✅ Checklist

- [x] `pom.xml` version bumped to `1.0.14`
- [x] Chinese and English Article 18 & 19 added under `docs/article/`
- [x] `ToolScanner` supports complex object parameters and multi-level priority descriptions
- [x] History multi-application three-layer isolation and thread safety verified
- [x] Agent callbacks migrated to Consumer mode; `onLlm` hook available
- [x] New `ToolScannerTest` unit tests added
- [x] No breaking API changes (manual builder maintains external interface compatibility)
