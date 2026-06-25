# Pull Request: dev-1.0.18 → master

## 概述 / Overview

本 PR 将 `dev-1.0.18` 合并至 `master`，在 **1.0.17** 基础上新增两项执行控制能力：**SubAgent 私有工具配置**（`SubAgentConfig.ownTools`，为子 Agent 声明仅自身可见的私有工具提供标准数据字段）与 **McpAgentExecutor 末次工具结果直返**（`returnLastToolResult`，工具调用已经产出满足目标的结果时，跳过 LLM 二次改写，直接返回观测结果）。

相对 `master` 共 **2** 个提交，均为 **Feature**（SubAgent 私有工具字段、末次工具结果直返）。

---

## ✨ 主要变更 / Key Changes

### 🔒 SubAgent 私有工具配置 (SubAgentConfig.ownTools)

- **`SubAgentConfig.ownTools`**：新增 `List<Tool>` 字段，用于声明只属于该 SubAgent 内部执行器的私有工具
- **与 `allowedTools` 的区别**：`allowedTools` 是按名借用父级 Agent 已注册的共享工具（`List<String>`，运行时由 `injectParentTools` 注入）；`ownTools` 是子 Agent 完全私有持有的工具对象（`List<Tool>`），不会作为父级能力市场的能力对外暴露
- 这是数据层字段新增：调用方在用 `SubAgent.from(config, chainActor)` 构建执行器时，需要显式通过 `.tools(config.getOwnTools())` 把私有工具注入 Builder（`SubAgent.Builder.tools(...)` 为既有方法），便于上层框架统一从配置 / 注解中读取私有工具定义后按约定接入
- 引入 `org.salt.jlangchain.rag.tools.Tool` 类型依赖，字段注释说明了私有工具的作用范围

### ⚡ McpAgentExecutor 末次工具结果直返 (returnLastToolResult)

- **`McpAgentExecutor.Builder.returnLastToolResult(boolean)`**：新增配置项，工具调用已经产出满足用户目标的观测结果时，跳过 LLM 对结果的二次改写，直接把该结果作为最终答案返回
- **实现方式**：新增 `LAST_TOOL_RESULT` 上下文存储键，每次工具观测完成后写入 `ContextBus`；流程链路上新增一个 `TranslateHandler`，在 `returnLastToolResult=true` 且本轮输出类型为 `ChatGeneration` 时，用存储的末次工具结果直接替换 LLM 生成内容
- **系统提示词联动**：开启该选项后，会自动在 `systemPrompt` 末尾追加一段规则，提示模型"工具结果已满足目标时应停止调用并结束，最终回复保持精简，只确认成功/失败，不展开改写"
- 新增 `McpAgentExecutorReturnLastToolResultTest` 专项测试类验证该行为

---

## 📋 环境变量 (Environment Variables)

与 1.0.17 保持一致，无新增环境变量。

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

- `McpAgentExecutorReturnLastToolResultTest`：验证 `returnLastToolResult(true)` 时，工具调用产出的观测结果被直接作为最终 `ChatGeneration` 返回，未经 LLM 二次改写
- `SubAgentConfig.ownTools`：字段读写与 `SubAgent.Builder.tools(...)` 手动注入路径验证通过

---

## 📦 版本 (Version)

- 发布版本：**1.0.18**（`master` 当前为 **1.0.17**）
- 基于 `salt-function-flow` 流程编排
- Java 17+，Spring Boot 3.2+

---

## ✅ 检查清单 (Checklist)

- [ ] `pom.xml` 版本号由 `1.0.18-SNAPSHOT` 更新至 `1.0.18`
- [ ] `docs/guide/quickstart.md` / `quickstart_cn.md` 依赖版本号同步更新至 `1.0.18`
- [x] `SubAgentConfig.ownTools` 字段定义完整，注释清晰说明与 `allowedTools` 的区别
- [x] `McpAgentExecutor.Builder.returnLastToolResult` 实现完整，`LAST_TOOL_RESULT` 透传与 `TranslateHandler` 替换逻辑验证通过
- [x] 系统提示词追加规则按 `returnLastToolResult` 开关正确生效
- [x] `McpAgentExecutorReturnLastToolResultTest` 测试通过
- [x] 无破坏性 API 变更（均为新增字段 / 新增 Builder 方法，默认值不改变既有行为）
