# Pull Request: dev-1.0.17 → master

## 概述 / Overview

本 PR 将 `dev-1.0.17` 合并至 `master`，在 **1.0.16** 基础上补全可观测性闭环：**Skill / SubAgent Token 用量回调**（`onTokenUsage`）直接透传 `AgentTokenUsageEvent`，配合修复 **OpenAI 协议重复 `temperature` 字段**的请求参数冲突，同时更新文档教程目录（英文 29 篇 / 中文 31 篇）。

相对 `master` 共 **2** 个提交，涵盖 **Feature**（Token 回调透传）、**Fix**（OpenAI 请求参数冲突）、**Docs**（教程目录更新）。

---

## ✨ 主要变更 / Key Changes

### 📊 Skill / SubAgent Token 用量回调 (onTokenUsage)

- **`Skill.Builder.onTokenUsage(Consumer<AgentTokenUsageEvent>)`**：新增 Token 用量回调，在 Skill 内部每次 LLM 调用完成后触发，回调对象与主 Agent 的 `tokenUsageConsumer` 保持一致
- **`SubAgent.Builder.onTokenUsage(Consumer<AgentTokenUsageEvent>)`**：SubAgent 同步新增，支持子 Agent 层级的 Token 用量上报
- **透传机制**：`Skill` / `SubAgent` 在 `buildExecutor()` 时将 `onTokenUsage` 注入内部 `McpAgentExecutor`，回调链路完整
- 与 1.0.16 的 `AiTokenUsage` / `AgentTokenUsageEvent` 结构完全兼容，无破坏性变更

### 🔧 修复 OpenAI 请求参数冲突 (temperature 重复字段)

- **根因**：`AiChatInput.temperature` 通过专用 setter 设置后，`extraParams` 中若同样包含 `temperature`，`@JsonAnyGetter` 序列化时会产生重复字段，导致 DeepSeek / OpenAI 兼容接口返回 HTTP 400
- **修复**：`OpenAIConver.convertRequest()` 在处理 `extraParams` 时，先从 Map 中 `remove("temperature")`，用提取值覆盖专用 setter 的值，再将剩余字段写入 `extraFields`
- 其他标准字段（`top_p`、`max_tokens` 等）若出现在 `extraParams` 中，同样不会重复序列化（已由专用 setter 覆盖）

### 📚 文档更新

- **README（英文）**：教程数量从 24 增至 29，新增 Function-Calling ReAct、Manager Agent、Client Agent、Hybrid MCP Agent 独立条目，以及 Agent 停止类型、Human-in-the-Loop、可观测性、推理内容等
- **README_CN（中文）**：教程数量从 24 增至 31，新增国产厂商序列链接口、PDF 文档问答 Agent、Agent 停止类型、Human-in-the-Loop 等条目
- MCP 相关教程拆分为独立条目，结构更清晰

---

## 📋 环境变量 (Environment Variables)

与 1.0.16 保持一致，无新增环境变量。

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

- `Skill` / `SubAgent` `onTokenUsage` 回调：在 Skill / SubAgent 执行流中验证 `AgentTokenUsageEvent` 正确触发
- `OpenAIConver` 修复：验证包含 `temperature` 的 `extraParams` 不再产生重复字段，DeepSeek extended thinking 场景通过

---

## 📦 版本 (Version)

- 发布版本：**1.0.17**（`master` 当前为 **1.0.16**）
- 基于 `salt-function-flow` 流程编排
- Java 17+，Spring Boot 3.2+

---

## ✅ 检查清单 (Checklist)

- [x] `pom.xml` 版本号已更新至 `1.0.17`
- [x] `docs/guide/quickstart.md` / `quickstart_cn.md` 依赖版本号已更新至 `1.0.17`
- [x] `Skill.Builder.onTokenUsage` 完整实现并在 `buildExecutor()` 中透传
- [x] `SubAgent.Builder.onTokenUsage` 完整实现并在 `buildExecutor()` 中透传
- [x] `OpenAIConver.convertRequest()` 修复 `temperature` 重复字段，400 错误消除
- [x] README / README_CN 教程目录数量与条目已更新
- [x] 无破坏性 API 变更（新增 Builder 方法，不影响已有调用方）
