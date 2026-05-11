# Pull Request: dev-1.0.15 → master

## 概述 / Overview

本 PR 将 `dev-1.0.15` 合并至 `master`，在 **1.0.14** 基础上重点引入多 Agent 协作的三大核心能力：**Skill 技能封装**、**SubAgent 自主子代理**、**Agent 停止与恢复**，以及 `McpAgentExecutor` 工具调用展示优化。这三个能力共同构成了 j-langchain 的分层 Agent 体系，使复杂多 Agent 系统的构建从手工编排走向标准化封装。

相对 `master` 共 **5** 个提交，涵盖 **Feature**（Skill、SubAgent）、**Refactor**（Agent 停止异常、工具调用处理）与 **Docs**（Article 20–24，中英文）。

---

## ✨ 主要变更 / Key Changes

### 🎯 Skill 技能封装 (Skill Agent)

- **`Skill` 类**：子工作流的标准封装单元，对主 Agent 暴露为普通 `Tool`，内部运行独立的 `McpAgentExecutor` 循环
- **`SkillConfig`**：纯数据配置对象，支持 classpath 加载、数据库读取、代码构造三种来源等价
- **`ClasspathSkillConfigLoader`**：解析 `skills/<name>/SKILL.md` 目录约定，自动加载 `references/`（知识注入）、`scripts/`（脚本工具）、`agents/`（嵌入式子代理）
- **工具来源三层体系**：脚本工具（`scripts/`）→ 显式注册（`.tools()`）→ `allowedTools` 借用，三层合并、互不干扰
- **三种运行模式**：classpath + Master（工具借用）、代码构造（无文件依赖）、独立运行（直接 `skill.invoke()`）
- **`verbose` + 精细回调**：`[skill:<name>]` 前缀日志；`onLlm` / `onToolCall` / `onObservation` 三级回调钩子

### 🤖 SubAgent 自主子代理 (SubAgent)

- **`SubAgent` 类**：拥有自有工具的子代理封装，对主 Agent 暴露为普通 `Tool`，与 `Skill` 接口一致可混用
- **`SubAgentConfig`**：纯数据配置对象，支持 `AGENT.md` classpath 加载、代码构造
- **`ClasspathSubAgentConfigLoader`**：解析 `agents/<name>/AGENT.md`，支持 `skills` 字段的 Skill 知识静态注入
- **LLM 三级解析优先链**：显式注入（`.llm()`）> `model: inherit`（继承主 Agent）> `model: <name>` + `llmFactory`（按名工厂构建）
- **`allowedTools` 白名单**：SubAgent 从父 Agent 借用声明工具，最小权限原则，主 Agent `build()` 时自动过滤注入
- **SKILL 内嵌 SubAgent**：`skills/<name>/agents/*.md` 自动解析为 `SubAgentConfig`，注册为 Skill 内部执行器的工具；日志前缀升级为二级 `[skill:<s>><a>]`
- **延迟初始化**：`injectLlm` / `injectLlmFactory` / `injectParentTools` 任意顺序调用，首次 `invoke()` 前完成执行器构建

### ⏸️ Agent 停止与恢复 (Stop & Resume)

- **`agent.stop()`**：发出停止信号，Agent 在当前工具返回后的安全检查点中止，保证工具调用原子性
- **`AgentStoppedException`**：携带 `partialContext`（已完成 `AgentStep` 列表），中断即保存进度
- **停止信号级联**：通过 `ContextBus` 从主 Agent 透传至 SubAgent 及 Skill 内部执行器，整个调用链同步停止
- **`agent.invoke(question, partialCtx)`**：断点续传，跳过已完成步骤
- **`FullContext.createWithSteps()`**：将旧步骤注入新指令，实现"带着进度换方向"
- **三种恢复策略**：重新开始 / 断点续传 / 旧步骤 + 新指令

### 🔧 McpAgentExecutor 工具调用展示优化

- 更新消息格式化逻辑，工具调用日志展示更清晰，`onToolCall` 回调输出标准化

### 📚 文档与示例 (Documentation & Samples)

- **Article 20 — 双 Agent 自我纠错代码生成**（中英文）
- **Article 21 — Proposer-Critic 多轮辩论**（中英文）
- **Article 22 — Skill Agent 技能封装**（中英文）
- **Article 23a — SubAgent 基础**（中英文）
- **Article 23b — SubAgent 进阶：LLM 策略、工具借用与 Skill 嵌套**（中英文）
- **Article 24 — Agent 停止与恢复**（中英文）
- **设计文档**：`docs/design/skill.md`、`docs/design/subagent.md`（含完整架构图、数据结构、使用示例）

---

## 📋 环境变量 (Environment Variables)

与 1.0.14 保持一致，无新增环境变量。

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

- `Article22SkillAgent`：覆盖三种 Skill 运行模式（classpath + Master / 代码构造 / 独立运行）
- `Article23SubAgent`：覆盖 7 个 SubAgent 场景（独立 / Master / 代码构造 / inherit / llmFactory / allowedTools / 内嵌 SubAgent）
- `Article24StopAndResume`：覆盖 5 个停止与恢复场景（基础 stop / partialContext / SubAgent 信号传播 / 断点续传 / 新指令注入旧步骤）

---

## 📦 版本 (Version)

- 发布版本：**1.0.15**（`master` 当前为 **1.0.14**）
- 基于 `salt-function-flow` 流程编排
- Java 17+，Spring Boot 3.2+

---

## ✅ 检查清单 (Checklist)

- [x] `pom.xml` 版本号已更新至 `1.0.15`
- [x] `Skill` / `SkillConfig` / `ClasspathSkillConfigLoader` 实现完整
- [x] `SubAgent` / `SubAgentConfig` / `ClasspathSubAgentConfigLoader` 实现完整
- [x] LLM 三级解析优先链（显式 / inherit / llmFactory）行为正确
- [x] `allowedTools` 白名单注入逻辑经 Master `build()` 验证
- [x] `stop()` 信号在 Master → SubAgent → Skill 链路上级联传播
- [x] `AgentStoppedException.getPartialContext()` 携带已完成步骤
- [x] `FullContext.createWithSteps()` 新指令注入旧步骤验证通过
- [x] 中英文 Article 20–24 文档已更新至 `docs/article/`
- [x] 设计文档 `skill.md`、`subagent.md` 已归档至 `docs/design/`
- [x] 无破坏性 API 变更（Skill / SubAgent 均以 Tool 接口对主 Agent 透明）
