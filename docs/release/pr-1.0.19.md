# Pull Request: dev-1.0.19 → master

## 概述

在 1.0.18 基础上，完善 Skill 兼容与脚本执行、自定义模型接入、结构化输出及长期任务记忆，并修复 HTTP 和流式处理问题。目标发布版本为 **1.0.19**，当前处于发布准备阶段，尚未发布。

## 主要变更

### Skill 兼容与脚本执行

- 完善 Claude Code 风格 `SKILL.md` 目录加载和配置解析，保留 license、metadata 等信息。
- 增加 `ReferencesMode.INLINE` / `LAZY`：默认内联参考文档，也可通过 `read_reference` 按需读取。
- 兼容模式在未声明 `allowedTools` 时提供受限工作区文件工具，支持创建目录、读取和写入文件；代码构造的 Skill 默认不启用兼容模式。
- 改进脚本参数拆分、原目录执行及 Python 包模块调用，排除不适合作为工具的辅助模块。
- 增加 Article30 示例及 Skill、脚本、配置加载回归测试。

### 模型与请求处理

- 新增 `ChatCustom` / `CustomActuator`，支持自定义 OpenAI Chat Completions 兼容接口；通过 `models.custom.chat-url` 和 `models.custom.chat-key` 配置，密钥可回退至 `CUSTOM_KEY`，无默认服务地址。
- 新增 `BaseChatModel.withJsonMode()`，支持的实现请求 JSON 输出。具体服务是否支持仍需验证；这不等于统一的 JSON Schema 校验保证。
- 修复第三方供应商请求 JSON 序列化和 HTTP 请求错误处理，调整混元配置。
- 修复流式迭代器完成后可能丢弃消息的问题。

### 记忆与上下文

- 新增 `PeriodicConversationSummaryMemory` 及对应 Reader / Storer：原始对话达到 `maxSize` 时整批压缩，合并至摘要后清空原始缓冲区。
- 新增 `AgentTaskContext.getEarlyStepsSummary()` / `restoreSummary(String)`，支持在重建上下文时恢复早期步骤摘要；调用方负责持久化。
- 完善 Agent、Skill、SubAgent 间任务上下文摘要处理。

## 兼容性变更与迁移

### 会话 ID 改为 String

`ConversationStorage.loadAll/append/replace/clear` 的 `appId`、`userId`、`sessionId` 参数由 `Long` 改为 `String`；相关历史记忆 Builder 同步调整。

- 调用处由 `.sessionId(123L)` 改为 `.sessionId("123")`，其他 ID 同理。
- 自定义 `ConversationStorage` 实现需要更新方法签名并重新编译。
- 已有持久化存储需要明确 ID 转换规则，保持历史数据查询键一致；不要将空 ID 转为字符串 `"null"`。
- 这是源码及二进制不兼容变更，依赖项目需要同步修改并重新构建。

### 移除 Coze 支持

移除 `ChatCoze`、Coze actuator、OAuth 辅助类及相关 DTO；原有 Coze 配置不再用于内置模型接入。需要 Coze 的应用应保留旧版本或自行实现适配。`ChatDoubao` 保留；`ChatCustom` 面向 Chat Completions 兼容接口，不是 Coze 专用 API 的直接替代。

## 验证结果

- [x] `mvn -o clean verify -Plocal-tests -Dgpg.skip=true`：本地回归 91 项，失败 0、错误 0、跳过 0；生成主 JAR、源码包、Javadoc 包。
- [x] 本地测试配置及验证步骤记录于 [发布验证说明](../guide/release-validation.md)。
- [ ] `AgentExecutorControlTest` 的 4 项及 `McpAgentExecutorReturnLastToolResultTest` 的 1 项真实模型验收：前次运行因模型免费额度耗尽失败，待恢复可用额度后重跑。
- [ ] `SkillCreatorClaudeCompatRegressionTest`：前次运行因脚本 Python 缺少 PyYAML 导致校验和打包失败，待补齐环境后重跑。
- [ ] 其余供应商、RAG、TTS 等集成测试按对应服务配置验收；尚未完成全量测试。
- [ ] GPG 签名及 Maven Central 发布验证。

本地构建仍有现有编译与 Javadoc 警告。上述本地验证不代表真实供应商全部验收通过。

## 发布检查清单

- [x] `pom.xml` 设置为 `1.0.19`。
- [x] 中英文 README、Quick Start 依赖示例同步为 `1.0.19`。
- [x] 中英文发布说明、API 增补及迁移提示。
- [ ] 完成集成验收及 GPG 签名验证。
- [ ] 检查 regnexe-agent、regnexe-cli 的依赖与兼容性。
- [ ] 提交、合并、创建发布标签及正式发布。

运行环境维持 Java 17、Spring Boot 3.2.12，`salt-function-flow` 为 1.1.8。历史发布文档保留各自版本号。
