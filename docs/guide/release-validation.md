# 发布前验证

使用 Java 17 和 Maven，在项目根目录运行本地回归及打包检查：

```bash
mvn clean verify -Plocal-tests -Dgpg.skip=true
```

`local-tests` 显式选择 Agent 记忆、会话记忆、摘要压缩、Skill 工作区、脚本执行和配置加载测试，不需要模型 API、Spring 上下文或 MCP 服务。脚本测试需要本机 Python；依赖已缓存时可添加 `-o` 运行。该配置不改变默认 `mvn test` 的测试选择。

本地检查跳过 GPG 签名，不执行发布。成功后仍需验证真实服务和发布签名，不能据此声称全部测试通过。

## 真实服务验收

以下命令会调用真实模型；运行前确认测试配置、凭证和模型额度，可能产生费用：

```bash
mvn test -Dtest=AgentExecutorControlTest,McpAgentExecutorReturnLastToolResultTest
mvn test -Dtest=SkillCreatorClaudeCompatRegressionTest
```

Skill Creator 测试还需要本机安装 Claude Code 的 skill-creator 插件，并确保脚本实际使用的 `python` 能执行 `import yaml`（PyYAML）。Spring 测试上下文还会初始化配置的 MCP 服务，需要相应运行环境。模型额度不足或依赖缺失属于验收阻塞，不能通过跳过测试当作验收成功。

其他供应商、向量库、TTS 等测试也依赖对应服务。完整 `mvn clean test` 应在这些依赖准备齐全后执行。

## 发布构建

集成验收完成并准备正式版本、升级说明后，运行不带 `-Dgpg.skip=true` 的构建来验证签名。`pom.xml` 配置了 Central 自动发布，`deploy` 会触发上传及发布，只在正式发布步骤运行。
