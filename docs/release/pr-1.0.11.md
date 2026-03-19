# Pull Request: dev-1.0.11 → master

## 概述 / Overview

本 PR 将 `dev-1.0.11` 分支合并至 `master`，包含文档重构、TTS 语音合成、Coze 模型集成、MCP 工具调用、RAG 文档加载等多项功能增强。

---

## ✨ 主要变更 / Key Changes

### 📚 文档 (Documentation)

- **README 重构**：中英文版全面更新，统一结构与内容
- **快速开始**：新增 `docs/guide/quickstart.md`、`docs/guide/quickstart_cn.md`，5 分钟入门
- **API 参考**：新增 `docs/api/reference.md`（英文）、`docs/api/reference_cn.md`（中文）
- **示例代码**：新增 `MyFirstAIApp`，涵盖 hello、动态路由、并行、流式、JSON、事件监控
- **API Key 配置**：补齐所有支持的模型厂商环境变量说明

### 🎤 TTS 语音合成 (Text-to-Speech)

- 新增 `TtsBase` 基类，支持流式语音合成
- **阿里云 TTS**：`AliyunTts`，支持 xiaoyun 等音色
- **豆包 TTS**：`DoubaoTts`
- **括号内容过滤**：自动过滤括号内文本，优化 TTS 朗读效果
- **智能分句**：基于标点的流式分句与合成

### 🤖 Coze 模型集成

- 新增 `ChatCoze`，支持扣子大模型
- **OAuth 2.0 认证**：`CozeOAuthHelper`，支持 client_id + 私钥
- **SSE 实时推送**：`CozeActuator`、`CozeListener`，流式接收消息

### 🔧 MCP 工具调用 (Model Context Protocol)

- **McpClient**：支持 Stdio、SSE、HTTP 三种连接方式
- **McpManager**：工具管理、`manifest()`、多服务器分组
- **ToolDesc / ToolResult**：工具描述与调用结果封装
- **环境变量占位**：配置支持 `${VAR}`、`${VAR:default}` 替换

### 🛠️ 工具调用 (Tool Calling)

- `AiChatInput` / `AiChatOutput` 增加 tools、tool_calls 等字段
- `BaseChatModel` 及各 LLM 模型支持 `tools()` 注入
- 新增 `ToolMessage` 表示工具调用结果

### 📄 RAG 文档加载

- **ApachePoiDocxLoader**：Word DOCX 加载
- **ApachePoiDocLoader**：Word DOC 加载
- 基于 Apache POI 实现

### 🔄 核心优化 (Core)

- **JsonOutputParser**：增强对 `ChatGeneration` 类型的处理
- **ChatGenerationChunk**：修正流式输出逻辑，新增 `finallyCalls` 机制
- **流处理**：调整以支持通用生成类型
- **OpenAI 请求/响应**：重构，支持更多高级特性

---

## 📋 环境变量 (Environment Variables)

| 变量名 | 说明 |
|--------|------|
| CHATGPT_KEY | OpenAI |
| ALIYUN_KEY | 阿里云千问 |
| MOONSHOT_KEY | Moonshot (Kimi) |
| DOUBAO_KEY | 豆包 |
| COZE_KEY | 扣子 (或 OAuth) |
| OLLAMA_KEY1 | Ollama |
| ALIYUN_TTS_KEY | 阿里云 TTS |
| DOUBAO_TTS_KEY | 豆包 TTS |

---

## 🧪 测试 (Testing)

- 新增 `MyFirstAIApp` 集成示例
- 新增 Coze、TTS、MCP、DOCX 等单元测试
- 配置文件 `mcp.server.config.json`、`mcp.config.json`

---

## 📦 版本 (Version)

- 发布版本：**1.0.11**
- 基于 `salt-function-flow` 流程编排
- Java 17+，Spring Boot 3.2+

---

## ✅ 检查清单 (Checklist)

- [x] 文档已更新
- [x] 中英文 README 同步
- [x] 示例代码可运行
- [x] 测试通过
- [x] 无破坏性变更
