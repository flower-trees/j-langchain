# Pull Request: dev-1.0.13 → master

## 概述 / Overview

本 PR 将 `dev-1.0.13` 合并至 `master`，在 **1.0.12** 基础上扩展多厂商 LLM、引入 **AgentExecutor / McpAgentExecutor** 与 **@AgentTool** 工具扫描、完善 MCP Function Calling 链路，并配套系列教程与示例代码；同时修正 Agent 执行器中观察截断、Final Answer 抽取与 MCP 迭代等若干问题。

相对 `master` 共 **15** 个提交，主题涵盖 **Feature**（多厂商 LLM、`AgentExecutor`、`McpAgentExecutor`、`@AgentTool`、MCP 文档与示例）、**Fix**（MCP 消息与迭代、观察截断等）与 **Refactor**（执行器继承与 Final Answer、文档结构）。统计：`git diff master..HEAD` 为 **61** 文件变更，**5574** 行新增、**232** 行删除。

---

## ✨ 主要变更 / Key Changes

### 🤖 Agent 与 MCP 执行器 (Agent & MCP)

- **AgentExecutor**：封装 ReAct 循环，简化 Agent 构建与调用
- **McpAgentExecutor**：支持模型侧 **Function Calling** 与 MCP 工具编排
- **继承与抽取优化**：执行器继承底层可执行类型；优化 Final Answer 提取与工具参数处理
- **问题修复**：MCP 智能体工具调用消息、迭代限制、由模型幻觉导致的观察截断等

### 🛠️ 工具定义与扫描 (Tooling)

- **@AgentTool / @Param**：声明式多参数工具定义
- **ToolScanner**：扫描并注册带注解的工具方法
- **McpManager**：增强（含与 Agent 输入侧的 manifest 等协作，详见代码与 11～14 文）

### 🤖 多厂商 LLM (LLM Vendors)

新增对话模型封装及对应 **Actuator** Bean（`JLangchainConfig` 注册），并补充单元测试：

- **DeepSeek** — `ChatDeepseek`
- **腾讯混元** — `ChatHunyuan`
- **百度千帆** — `ChatQianfan`
- **智谱 GLM** — `ChatZhipu`
- **MiniMax** — `ChatMinimax`
- **零一万物 Yi** — `ChatLingyi`
- **阶跃星辰 Step** — `ChatStepfun`

### 📚 文档与示例 (Documentation & Samples)

- **文章系列**（`docs/article/001/`）：重构 **08-mcp**；新增 **09～16**（AgentExecutor、航司比价、MCP ReAct / Manager / Client / 混合、旅行规划嵌套、双 Agent 客服）；更新 **001/README.md** 目录、阅读顺序、代码路径与运行环境表
- **示例类**（`demo/article/`）：`Article08Mcp` 扩展；新增 `Article09`～`Article16` 与独立工具内部类，便于对照文档运行
- **README / README_CN**：补充 7 家新厂商说明、并行链示例与 `concurrent` 用法、能力对比表中「多厂商」数量等

### 🔗 调用链示例 (Chain)

- **并行执行**：README 中并行笑话/诗歌示例改为基于 `concurrent` + `ctx.getResult(flowId)` 的写法（与 `Article02ChainPatterns`、`ChainDemoTest` 等一致）

---

## 📋 环境变量 (Environment Variables)

在 1.0.11 已有变量基础上，新增各厂商默认占位（亦可通过 `models.<vendor>.chat-key` 配置）：

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

- 新增上述厂商的 `Chat*Test` 调用样例
- 新增/调整 `Article02`、`Article04`、`Article08`～`Article16` 等演示与 `ChainDemoTest`
- 更新测试侧 `mcp.config.json`、`logback.xml` 等配置

---

## 📦 版本 (Version)

- 发布版本：**1.0.13**（`master` 当前为 **1.0.12**）
- 基于 `salt-function-flow` 流程编排
- Java 17+，Spring Boot 3.2+

---

## ✅ 检查清单 (Checklist)

- [x] 文档与文章目录已更新（含 MCP / AgentExecutor 系列）
- [x] 中英文 README 与能力说明同步
- [x] 新增示例与测试覆盖新模型与 Agent 路径
- [x] 环境变量与配置项已可通过文档或上表查阅
- [x] 无意的破坏性变更已在审阅中关注（并行链 README 示例为 API 用法更新）
