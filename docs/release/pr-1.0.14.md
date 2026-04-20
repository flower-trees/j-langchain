# Pull Request: dev-1.0.14 → master

## 概述 / Overview

本 PR 将 `dev-1.0.14` 合并至 `master`，在 **1.0.13** 基础上重点强化工具参数描述能力与历史管理的多应用支持，主要包括：**复杂对象类型工具参数**支持、**@ParamDesc 多级优先级参数描述**、**History 多应用 + 线程安全重构**、Agent 执行器日志回调 Consumer 模式改造，以及并行 Agent 与 RPC 零侵入接入 AI 工具两篇新教程。

相对 `master` 共 **11** 个提交，涵盖 **Feature**（复杂对象参数、多级参数描述）、**Refactor**（历史管理、Agent 回调、Builder、消息类）与 **Docs**（Article 18、19）。`git diff master..HEAD`：**67** 文件变更，**5575** 行新增，**374** 行删除。

---

## ✨ 主要变更 / Key Changes

### 🛠️ 工具参数增强 (Tool Parameter Enhancement)

- **复杂对象类型参数**（`Feature: rag`）
  - 扩展 `@Param` 注解，支持标注在对象字段（`ElementType.FIELD`）上
  - 新增 `isComplexType` 方法识别需要 JSON 反序列化的参数类型
  - 新增 `ObjectSchema` 记录类，自动生成复杂对象的 Schema 描述与 JSON 示例
  - 集成 Jackson `ObjectMapper`，支持将 Action Input JSON 字符串反序列化为复杂对象并调用对应方法
  - 补充完整单测，覆盖单字符串、多参数、复杂对象参数场景

- **多级优先级参数描述 `@ParamDesc`**（`Feature: annotation`）
  - 新增 `@ParamDesc` 注解及 `AgentTool.params` 属性，支持第三方对象参数描述
  - 参数描述优先级：`@AgentTool.params` > VO 字段 `@Param` > 方法参数 `@Param`
  - 集成 Spring AOP 工具，穿透代理类获取真实注解信息
  - 支持企业 RPC 接口（Dubbo / Feign）**零侵入**接入 AI 工具的最佳实践

### 🔧 Tool Scanner 优化 (Tool Scanner)

- **参数处理逻辑优化**（`Refactor: tool`）：重构 `ToolScanner` 参数扫描流程，提升参数解析的准确性与可维护性

### 📜 历史管理重构 (History Management)

- **多应用 + 线程安全**（`Refactor: history`）
  - `HistoryBase` 新增 `appId`、`userId`、`sessionId` 字段
  - `MemoryHistory` 存储结构从 `Map` 改为三层嵌套 `ConcurrentHashMap`（`appId → userId → sessionId`），天然支持多应用隔离
  - 移除 `init` 方法，改为 `getOrCreate` 懒加载模式
  - `MemoryHistoryStorer` 新增 `maxSize` 控制最大历史条数
  - 读写操作加同步机制，保障并发安全

### 🤖 Agent 执行器改造 (Agent Executor)

- **日志回调 Consumer 化**（`Refactor: agent`）
  - `ThoughtLogger`、`ObservationLogger`、`ToolCallLogger` 重命名为对应的 Consumer 形式
  - 新增 `onLlm` 回调钩子，在模型调用前捕获完整输入
  - `AgentExecutor` 插入 `emitBeforeLlm` 处理器，支持 LLM 输入监听
  - `McpAgentExecutor` 补充 Chat Prompt Value 格式化能力

### 🏗️ 核心重构 (Core Refactor)

- **Builder 手动实现**（`Refactor: common`）：移除 Lombok `@Builder`，手动实现 Builder 模式，增强可控性
- **EqualsAndHashCode**（`Refactor: core`）：为消息类与 Loader 类补充 `@EqualsAndHashCode` 注解
- **字段初始化**（`Refactor: core`）：将类级别字段初始化移至构造器，符合最佳实践

### 📚 文档与示例 (Documentation & Samples)

- **Article 18 — 并行 Agent 并发调研**（中英文）
  - 场景：多 Agent 并行收集信息后汇总，覆盖 `concurrent` + `ctx.getResult(flowId)` 用法
  - 示例类：`Article18ParallelTravelResearch`

- **Article 19 — RPC 零侵入接入 AI 工具**（中英文，原 Dubbo 专题精简通用化）
  - 场景：Dubbo / Feign 等 RPC 接口通过 `@ParamDesc` + Spring AOP 代理零侵入暴露为 AI 工具
  - 示例类：`Article19RpcMcpTools`

---

## 📋 环境变量 (Environment Variables)

与 1.0.13 保持一致，无新增环境变量。

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

- 新增 `ToolScannerTest`，覆盖单字符串参数、多参数、复杂对象参数三类场景
- 新增 `Article18ParallelTravelResearch`、`Article19RpcMcpTools` 示例演示

---

## 📦 版本 (Version)

- 发布版本：**1.0.14**（`master` 当前为 **1.0.13**）
- 基于 `salt-function-flow` 流程编排
- Java 17+，Spring Boot 3.2+

---

## ✅ 检查清单 (Checklist)

- [x] `pom.xml` 版本号已更新至 `1.0.14`
- [x] 中英文 Article 18、19 文档已更新至 `docs/article/`
- [x] `ToolScanner` 支持复杂对象参数与多级优先级描述
- [x] History 多应用三层隔离与线程安全验证通过
- [x] Agent 回调 Consumer 化，`onLlm` 钩子可用
- [x] 新增 `ToolScannerTest` 单元测试覆盖
- [x] 无破坏性 API 变更（Builder 手动实现保持对外接口兼容）
