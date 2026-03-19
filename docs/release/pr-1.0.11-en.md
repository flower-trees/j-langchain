# Pull Request: dev-1.0.11 → master

## Overview

This PR merges `dev-1.0.11` into `master`, including documentation restructure, TTS speech synthesis, Coze model integration, MCP tool calling, RAG document loading, and other enhancements.

---

## ✨ Key Changes

### 📚 Documentation

- **README Restructure**: Full update of Chinese and English editions with unified structure
- **Quick Start**: Added `docs/guide/quickstart.md`, `docs/guide/quickstart_cn.md` for 5-minute onboarding
- **API Reference**: Added `docs/api/reference.md` (EN), `docs/api/reference_cn.md` (CN)
- **Sample Code**: Added `MyFirstAIApp` covering hello, dynamic routing, parallel, streaming, JSON, event monitoring
- **API Key Config**: Complete environment variable reference for all supported model vendors

### 🎤 TTS Speech Synthesis

- Added `TtsBase` base class with streaming synthesis support
- **Alibaba Cloud TTS**: `AliyunTts`, supports xiaoyun and other voices
- **Doubao TTS**: `DoubaoTts`
- **Bracket Content Filtering**: Auto-filter parenthesized text for better TTS output
- **Smart Sentence Splitting**: Punctuation-based streaming split and synthesis

### 🤖 Coze Model Integration

- Added `ChatCoze` for Coze LLM
- **OAuth 2.0 Auth**: `CozeOAuthHelper` with client_id + private key
- **SSE Streaming**: `CozeActuator`, `CozeListener` for real-time message delivery

### 🔧 MCP Tool Calling (Model Context Protocol)

- **McpClient**: Stdio, SSE, HTTP connection modes
- **McpManager**: Tool management, `manifest()`, multi-server grouping
- **ToolDesc / ToolResult**: Tool description and call result encapsulation
- **Env Placeholders**: Config supports `${VAR}`, `${VAR:default}` replacement

### 🛠️ Tool Calling

- `AiChatInput` / `AiChatOutput`: tools, tool_calls fields
- `BaseChatModel` and all LLMs support `tools()` injection
- Added `ToolMessage` for tool call results

### 📄 RAG Document Loading

- **ApachePoiDocxLoader**: Word DOCX loading
- **ApachePoiDocLoader**: Word DOC loading
- Apache POI based implementation

### 🔄 Core Improvements

- **JsonOutputParser**: Enhanced `ChatGeneration` handling
- **ChatGenerationChunk**: Fixed streaming logic, added `finallyCalls` mechanism
- **Flow Processing**: Adjusted for generic generation types
- **OpenAI Request/Response**: Refactored with advanced features

---

## 📋 Environment Variables

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

---

## 🧪 Testing

- Added `MyFirstAIApp` integration sample
- Added unit tests for Coze, TTS, MCP, DOCX
- Config files: `mcp.server.config.json`, `mcp.config.json`

---

## 📦 Version

- Release: **1.0.11**
- Based on `salt-function-flow` for flow orchestration
- Java 17+, Spring Boot 3.2+

---

## ✅ Checklist

- [x] Documentation updated
- [x] README synced (CN/EN)
- [x] Sample code runnable
- [x] Tests passing
- [x] No breaking changes
