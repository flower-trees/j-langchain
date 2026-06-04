# Pull Request: dev-1.0.16 → master

## 概述 / Overview

本 PR 将 `dev-1.0.16` 合并至 `master`，在 **1.0.15** 基础上重点增强 Agent 运行时控制与可观测性：**Agent 多种停止类型**、**Human-in-the-Loop 人机协作**、**AgentExecutor 控制流增强**、**Token 用量统计与回调**、**Agent 执行指标收集**、**LLM 推理内容支持**，以及 `ConversationMemory` 统一历史记忆接口、LLM `copy()` 实例复制、Groovy 脚本 + 文件系统加载器等基础能力补全。

相对 `master` 共 **17** 个提交，涵盖 **Feature**（控制流、可观测、记忆、LLM 能力）、**Fix**（LLM 实例共享）、**Docs**（Article 26–27，中英文）。

---

## ✨ 主要变更 / Key Changes

### ⏹️ Agent 停止类型体系 (Agent Stop Types)

- **`AgentAbortException`**：不可恢复的主动中止，携带 `AgentAbortReason`（枚举：TIMEOUT / MAX_FAILURES / USER_ABORT / TOOL_FATAL 等）
- **`AgentException`**：Agent 异常基类，统一 `AgentStoppedException`（可恢复停止）与 `AgentAbortException`（不可恢复中止）
- **`AgentPauseException`**：暂停异常，携带当前上下文，支持外部介入后恢复执行
- **三类停止语义**：Stopped（信号停止/可恢复）、Paused（人机协作暂停）、Aborted（硬性中止）
- **Article 26 — Agent 运行时停止类型**（中英文）

### 🙋 Human-in-the-Loop 人机协作

- **用户确认函数**：Agent 执行至关键节点时主动挂起，抛出 `AgentPauseException`，等待外部确认或修改后恢复
- **上下文透传**：暂停时保存完整 `AgentContext`，恢复时无缝接续
- **Article 27 — Human-in-the-Loop**（中英文）

### 🎛️ AgentExecutor 控制流增强

- **`maxDurationSeconds`**：全局执行时长硬限制，超时触发 `AgentAbortException(TIMEOUT)`
- **`maxConsecutiveToolFailures`**：连续工具失败上限，超出触发不可恢复中止
- **`toolRetry`**：单工具调用自动重试策略（次数 + 退避）
- **Context Resume**：`invoke(question, partialCtx)` 支持带上下文恢复，从已完成步骤续跑

### 📊 Token 用量统计与回调

- **`AiTokenUsage`**：结构化 Token 用量（promptTokens / completionTokens / totalTokens）
- **`recordLlmUsage`**：`BaseChatModel` 流式输出结束后自动收集 Token 用量
- **`AgentTokenUsageEvent`**：Agent 级 Token 用量事件，含 round 编号与 LLM 调用维度数据
- **`tokenUsageConsumer`**：`McpAgentExecutor` 注册回调，每轮 LLM 调用完成后触发 `onTokenUsage(event)`

### 📈 Agent 执行指标收集 (AgentExecutionMetrics)

- **`AgentExecutionMetrics`**：新增指标类，记录每轮 LLM 调用耗时与工具调用耗时
- **`BaseAgentTaskContext`**：上下文基类新增 `metrics` 字段，`FullContext` / `SlidingWindowContext` 自动采集
- **`Skill` / `SubAgent`**：指标采集接入，可汇聚子任务执行数据

### 🧠 推理内容支持 (Reasoning Content)

- **`AiChatOutput.reasoningContent`**：新增推理内容字段，用于承载 DeepSeek-R1 / o1 等模型的 `<think>` 过程
- **`AiChatInput.thinkingConfig`**：可选思维链配置，透传给底层模型
- **`BaseMessage.reasoningContent`**：消息层支持推理内容存储与传递
- **`OpenAIConver`**：OpenAI 协议转换层适配 reasoning_content 字段

### 📚 ConversationMemory 统一历史记忆接口

- **`ConversationMemory` 接口**：`storeHistory(HistoryInfos)` + `readHistory()` 统一 Storer/Reader 为单一对象
- **四个实现类**：`ConversationBufferMemory`、`ConversationBufferWindowMemory`、`ConversationSummaryMemory`、`ConversationSummaryBufferMemory`，分别委托对应 Storer + Reader
- Builder 模式：appId / userId / sessionId / maxSize / storage / llm 按需配置
- 与现有 Flow 的 Storer/Reader 体系完全兼容，不破坏已有使用

### 🔄 LLM 实例复制 (copy)

- **`BaseChatModel.copy()`**：抽象复制方法，返回同配置的独立实例，用于多 Agent 并发时隔离 LLM 状态
- 覆盖全部 **13 个**模型实现：Aliyun / DeepSeek / Doubao / Coze / Hunyuan / Lingyi / MiniMax / Moonshot / Ollama / OpenAI / Qianfan / StepFun / Zhipu
- **修复 LLM 实例共享 bug**：`AgentExecutor` Builder 中 `.llm()` 调用 `copy()` 防止多 Agent 场景下的实例竞争

### 🌐 HTTP 客户端超时配置

- **`JLangchainConfig.callTimeout`**：全局 HTTP/SSE 调用超时（毫秒）
- **`HttpSseClient` / `HttpStreamClient`**：透传 `callTimeout`，建立连接与读取流统一受控
- **`McpHttpConnection` / `McpSseConnection`**：MCP 连接层同步支持超时参数
- **`ServerConfig`**：MCP Server 配置新增 `callTimeout` 字段

### 🧩 Groovy 脚本支持 + 文件系统配置加载器

- **`FileSystemSkillConfigLoader`**：从文件系统任意目录加载 Skill 配置，不再局限于 classpath
- **`FileSystemSubAgentConfigLoader`**：对应的 SubAgent 文件系统加载器
- **`ScriptTool` Groovy 支持**：脚本工具引擎扩展，支持 Groovy 脚本作为工具实现

### ⚙️ Skill / SubAgent 批量构建方法

- **`McpAgentExecutor.skills(List<Skill>)`**：一次注册多个 Skill
- **`McpAgentExecutor.subAgents(List<SubAgent>)`**：一次注册多个 SubAgent
- 替代逐个 `.skill(s1).skill(s2)` 的链式调用，提升批量构建场景的简洁性

### 📚 文档与示例 (Documentation & Samples)

- **Article 26 — Agent 运行时停止类型**（中英文）：三类停止语义、API 用法、场景示例
- **Article 27 — Human-in-the-Loop**（中英文）：人机协作暂停/恢复、确认函数注册
- **设计文档**：`docs/design/agent-stop-and-resume.md`、`docs/design/agent-stop-and-resume-en.md`（完整状态机图、异常类层次、恢复策略对比）
- **API Reference** 更新：`docs/api/reference.md`、`docs/api/reference_cn.md`

---

## 📋 环境变量 (Environment Variables)

与 1.0.15 保持一致，无新增环境变量。

| 变量名 | 说明 |
|--------|------|
| CHATGPT_KEY | OpenAI |
| ALIYUN_KEY | 阿里云千问 |
| MOONSHOT_KEY | Moonshot (Kimi) |
| DOUBAO_KEY | 豆包 |
| COZE_KEY | 扣子（或 OAuth） |
| OLLAMA_KEY1 | Ollama |
| ALIYUN_TTS_KEY | 阿里云 TTS |
| DOUBAO_TTS_KEY | 豆包 TTS |
| DEEPSEEK_KEY | DeepSeek |
| HUNYUAN_KEY | 腾讯混元 |
| QIANFAN_KEY | 百度千帆 |
| ZHIPU_KEY | 智谱 AI |
| MINIMAX_KEY | MiniMax |
| LINGYI_KEY | 零一万物 |
| STEPFUN_KEY | 阶跃星辰 |

---

## 🧪 测试 (Testing)

- `AgentExecutorControlTest`：覆盖控制流场景（maxDurationSeconds / maxConsecutiveToolFailures / toolRetry / Token 用量回调）
- `Article26AgentStopTypes`：覆盖三类停止语义（Stopped / Paused / Aborted）及 AbortReason 枚举
- `ChainAgentContextTest`：覆盖 `AgentExecutionMetrics` 指标采集与上下文透传

---

## 📦 版本 (Version)

- 发布版本：**1.0.16**（`master` 当前为 **1.0.15**）
- 基于 `salt-function-flow` 流程编排
- Java 17+，Spring Boot 3.2+

---

## ✅ 检查清单 (Checklist)

- [x] `pom.xml` 版本号已更新至 `1.0.16`
- [x] `ConversationMemory` 接口及四个实现类完整，Builder 模式通过验证
- [x] `AgentAbortException` / `AgentPauseException` / `AgentException` 层次清晰
- [x] `AgentAbortReason` 枚举覆盖全部中止场景
- [x] `maxDurationSeconds` / `maxConsecutiveToolFailures` / `toolRetry` 控制逻辑经 `AgentExecutorControlTest` 验证
- [x] `AiTokenUsage` Token 统计与 `AgentTokenUsageEvent` 回调链路完整
- [x] `AgentExecutionMetrics` 指标采集接入 `FullContext` / `SlidingWindowContext`
- [x] `BaseChatModel.copy()` 13 个模型均已实现，LLM 实例共享 bug 已修复
- [x] `AiChatOutput.reasoningContent` OpenAI 协议转换层适配完整
- [x] `FileSystemSkillConfigLoader` / `FileSystemSubAgentConfigLoader` 可从任意目录加载
- [x] HTTP/SSE 超时参数从 `JLangchainConfig` 透传至 MCP 连接层
- [x] Human-in-the-Loop 暂停/恢复流程经 Article27 测试验证
- [x] 中英文 Article 26–27 文档已归档至 `docs/article/`
- [x] 设计文档已归档至 `docs/design/`
- [x] 无破坏性 API 变更（ConversationMemory 为新增接口；Storer/Reader 体系不变）
