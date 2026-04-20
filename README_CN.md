<div align="center">

# J-LangChain

**🚀 Java 版 LangChain —— 用 Spring Boot 分钟级构建 LLM 应用**

[![Maven Central](https://img.shields.io/maven-central/v/io.github.flower-trees/j-langchain.svg)](https://search.maven.org/artifact/io.github.flower-trees/j-langchain)
[![GitHub stars](https://img.shields.io/github/stars/flower-trees/j-langchain?style=social)](https://github.com/flower-trees/j-langchain)
[![GitHub forks](https://img.shields.io/github/forks/flower-trees/j-langchain?style=social)](https://github.com/flower-trees/j-langchain/fork)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](https://opensource.org/licenses/Apache-2.0)
[![Java](https://img.shields.io/badge/Java-17+-green.svg)](https://www.oracle.com/java/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.2+-green.svg)](https://spring.io/projects/spring-boot)

[English](./README.md) | [中文文档](#)

**不用再重复造轮子。** J-LangChain 是面向 Java/Spring Boot 开发者的生产级 AI 框架，一个依赖搞定：调用链编排、Agent、RAG、MCP 与 13+ 主流大模型。

⭐ **如果这个项目对你有帮助，请点个 Star，这对项目非常重要！** ⭐

</div>

---

## 🆚 为什么选 J-LangChain？

| 特性 | J-LangChain | LangChain4j | Spring AI |
|------|-------------|-------------|-----------|
| **多厂商 LLM** | ✅ 13+ 主流（OpenAI / DeepSeek / 千问 / Kimi…） | ✅ 5+ | ⚠️ 有限 |
| **流程编排** | ✅ 串行 / 并行 / 嵌套 / 条件路由 | ⚠️ 链式调用 | ⚠️ 基础 |
| **RAG** | ✅ 端到端（加载→分割→嵌入→向量） | ✅ 完整 | ⚠️ 部分 |
| **MCP 协议** | ✅ 3 种模式 + Function Calling + Agent | ❌ | ❌ |
| **AgentExecutor** | ✅ `@AgentTool` 一行启动 ReAct Agent | ⚠️ 手动 | ⚠️ 基础 |
| **TTS 支持** | ✅ 智能流式 + 自动过滤 | ❌ | ❌ |
| **事件监控** | ✅ 完整生命周期 | ⚠️ 基础 | ❌ |
| **Spring Boot** | ✅ 原生自动装配 | ✅ | ✅ |

---

## 🚀 5 分钟快速上手

### 1. 添加依赖

```xml
<dependency>
    <groupId>io.github.flower-trees</groupId>
    <artifactId>j-langchain</artifactId>
    <version>1.0.14</version>
</dependency>
```

```groovy
// Gradle
implementation 'io.github.flower-trees:j-langchain:1.0.14'
```

### 2. 导入配置

```java
@SpringBootApplication
@Import(JLangchainConfig.class)
public class Application {
    public static void main(String[] args) {
        SpringApplication.run(Application.class, args);
    }
}
```

### 3. 配置 API Key 并运行

```bash
export ALIYUN_KEY=sk-xxx...   # 或 CHATGPT_KEY / DEEPSEEK_KEY / OLLAMA_KEY1 ...
```

> 全部 18 个环境变量及对应模型类：[API 参考 → 环境变量](./docs/api/reference_cn.md#12-环境变量)

```java
@Autowired ChainActor chainActor;

FlowInstance chain = chainActor.builder()
    .next(PromptTemplate.fromTemplate("给我讲一个关于 ${topic} 的笑话"))
    .next(ChatAliyun.builder().model("qwen-plus").build())
    .next(new StrOutputParser())
    .build();

String result = chainActor.invoke(chain, Map.of("topic", "程序员"));
// → 为什么程序员喜欢暗色主题？因为光明会吸引 Bug！🐛
```

✅ Prompt → 大模型 → 解析器，就是这么简单。

---

## 💡 能做什么？

### ⚡ 流式输出 — 3 行实现打字机效果

```java
ChatOllama llm = ChatOllama.builder().model("llama3:8b").build();
AIMessageChunk chunk = llm.stream("简单解释一下量子计算");
chunk.getIterator().forEachRemaining(c -> System.out.print(c.getContent()));
// 量子| 计算| 利用| 量子比特|...
```

### 🤖 ReAct Agent — 一个注解，一个 Agent

```java
@AgentTool(description = "查询两城市间的机票价格")
public String searchFlights(
    @Param("出发城市") String from,
    @Param("目的城市") String to) {
    return from + "→" + to + "：经济舱 ¥999 / 商务舱 ¥3999";
}

AgentExecutor agent = AgentExecutor.builder()
    .llm(ChatAliyun.builder().model("qwen-plus").build())
    .toolScanner(new ToolScanner(this))
    .build();

agent.invoke("帮我查一下北京到上海最便宜的航班");
// Agent 自主推理 → 调用 searchFlights → 分析结果 → 给出答案 ✅
```

### 🔀 并行 Agent — 扇出调研，汇聚总结

```java
// 三个专项 Agent 并发运行，结果自动合并
FlowInstance researchChain = chainActor.builder()
    .concurrent(
        cAlias("flights",  flightAgent),
        cAlias("hotels",   hotelAgent),
        cAlias("weather",  weatherAgent)
    )
    .next(summaryAgent)   // 汇总三路结果
    .build();

// 参考教程 18：Article18ParallelTravelResearch.java
```

### 📚 RAG — 50 行搞定 PDF 问答

```java
PdfboxLoader loader = new PdfboxLoader("report.pdf");
List<Document> chunks = StanfordNLPTextSplitter.builder()
    .chunkSize(500).chunkOverlap(50).build()
    .splitDocument(loader.load());

Milvus vectorStore = new Milvus(new OllamaEmbeddings());
vectorStore.addDocuments(chunks);

List<Document> relevant = vectorStore.similaritySearch("这份报告的结论是什么？", 3);
String answer = chainActor.invoke(qaChain, Map.of("context", relevant, "question", "..."));
```

### 🔧 RPC 零侵入 — 两个注解把 Dubbo/Feign 接口变成 AI 工具

```java
// 完全不改动现有 RPC 接口
@AgentTool(description = "查询订单状态")
public OrderVO queryOrder(@Param("订单ID") String orderId) {
    return orderService.query(orderId);  // 原有 RPC 调用不变
}
// VO 字段加 @ParamDesc → 自动生成参数 Schema
// 参考教程 19：Article19RpcMcpTools.java
```

### 🎙️ LLM + TTS — 让 AI 开口说话

```java
AIMessageChunk llmStream = llm.stream("今天天气怎么样？");
TtsCardChunk audio = aliyunTts.stream(llmStream);
// 实时流式推送音频块；括号内容自动过滤后再合成
```

---

## 📖 19 篇教程：从 Hello World 到多 Agent 系统

每篇教程都有可运行的示例类，只需一个 API Key。

| 教程 | 你将构建什么 | 难度 |
|------|-------------|------|
| [01 · 第一个 AI 应用](./docs/article/001/01-hello-ai.md) | Prompt→LLM→Parser，流式，JSON 输出 | ⭐ |
| [02 · 5 种链式编排](./docs/article/001/02-chain-patterns.md) | 串行 / 并行 / 条件 / 嵌套链 | ⭐⭐ |
| [06 · 流式输出](./docs/article/001/06-streaming.md) | `stream` / `streamEvent` / stop，SSE 推送 | ⭐⭐ |
| [07 · 多模型接入](./docs/article/001/07-multi-model.md) | 统一 API 对接 13+ 主流大模型，动态切换 | ⭐ |
| [03 · RAG 全流程](./docs/article/001/03-rag-pipeline.md) | PDF → 分割 → 嵌入 → Milvus → 问答 | ⭐⭐⭐ |
| [04 · ReAct Agent](./docs/article/001/04-react-agent.md) | 工具定义、ReAct 推理循环从零实现 | ⭐⭐⭐ |
| [09 · AgentExecutor](./docs/article/001/09-agent-executor.md) | 一行启动 ReAct Agent，`@AgentTool` 注解 | ⭐⭐ |
| [10 · 航司比价 Agent](./docs/article/001/10-flight-compare-agent.md) | 多步工具调用：比价 + 自动订票 | ⭐⭐ |
| [05 · LLM + TTS](./docs/article/001/05-llm-tts.md) | 大模型→语音，流式合成 | ⭐⭐ |
| [08 · MCP 基础](./docs/article/001/08-mcp.md) | MCP 协议，McpManager / McpClient | ⭐⭐⭐ |
| [11–14 · MCP Agent](./docs/article/001/11-mcp-react-agent.md) | Function Calling ReAct，HTTP/NPX/混合 MCP | ⭐⭐⭐ |
| [15 · 旅行规划 Agent](./docs/article/001/15-travel-agent.md) | AgentExecutor 嵌入调用链 | ⭐⭐ |
| [16 · 客服双 Agent](./docs/article/001/16-multi-agent-executor.md) | ReAct + MCP 串联，投诉分析 + 文件写入 | ⭐⭐⭐ |
| [18 · 并行 Agent](./docs/article/001/18-parallel-agent-concurrent.md) | 3 Agent 并发调研，扇出/汇聚 | ⭐⭐⭐ |
| [19 · RPC 零侵入](./docs/article/001/19-rpc-vo-param.md) | Dubbo/Feign → AI 工具，2 个注解搞定 | ⭐⭐ |

➡️ [查看完整教程目录（含阅读顺序）→](./docs/article/001/README.md)

---

## ✨ 核心特性速览

### 🎯 13+ 主流大模型 — 统一 API 调用
OpenAI · Ollama · DeepSeek · 阿里云千问 · Moonshot (Kimi) · 豆包 · 扣子 · 腾讯混元 · 百度文心 · 智谱 GLM · MiniMax · 零一万物 · 阶跃星辰

### 🔗 调用链编排
串行 · 并行 · 嵌套 · 条件路由 · 流式输出 · 完整事件生命周期

### 📚 RAG
PDF / Word / OCR 文档加载 · 智能文本分割 · OpenAI / Ollama / 阿里云嵌入 · Milvus 向量存储

### 🤖 Agent & MCP
`AgentExecutor`（ReAct）· `McpAgentExecutor`（Function Calling）· `@AgentTool` / `ToolScanner` · MCP Stdio / SSE / HTTP · 多应用历史管理（线程安全）

### 🎤 TTS
阿里云 · 豆包 · 智能分句 · 括号自动过滤 · 实时流式音频

---

## 🏗️ 架构

```
┌─────────────────────────────────────────────────────────┐
│                 你的 Spring Boot 应用                    │
└────────────────────────┬────────────────────────────────┘
                         ▼
┌─────────────────────────────────────────────────────────┐
│                    J-LangChain Core                     │
│   ChainActor · PromptTemplate · OutputParser · History  │
└──────────┬───────────┬───────────┬──────────┬──────────┘
           ▼           ▼           ▼          ▼
      LLM (13+)      RAG         TTS         MCP
    多厂商对话    加载/分割/嵌入  流式合成    Stdio/SSE
   工具调用统一   向量存储查询   智能过滤    HTTP/FC
```

---

## 📄 文档

| | |
|--|--|
| [快速开始](./docs/guide/quickstart_cn.md) | 详细入门教程 |
| [中文系列教程](./docs/article/001/README.md) | 19 篇，含阅读顺序和可运行示例 |
| [English Tutorials](./docs/article/001-en/README.md) | English article series |
| [示例代码](./src/test/java/org/salt/jlangchain/demo/) | 30+ 可运行案例 |
| [API 文档](./docs/api/reference_cn.md) | 完整 API 参考 |

---

## 🤝 参与贡献

- 🐛 [提交 Bug](https://github.com/flower-trees/j-langchain/issues)
- 💡 [功能建议](https://github.com/flower-trees/j-langchain/issues)
- 🔧 [提交 PR](https://github.com/flower-trees/j-langchain/pulls)

```bash
git checkout -b feature/your-feature
git commit -m 'Add your feature'
git push origin feature/your-feature
# 然后提交 Pull Request
```

---

## 📄 开源协议

[Apache License 2.0](./LICENSE)

## 🙏 致谢

[LangChain](https://github.com/langchain-ai/langchain) · [salt-function-flow](https://github.com/flower-trees/salt-function-flow) · [Spring Boot](https://spring.io/projects/spring-boot)

---

<div align="center">

**⭐ 觉得有用？点个 Star 只需 2 秒，却能帮助项目走得更远 —— 感谢！⭐**

[快速开始](#-5-分钟快速上手) · [19 篇教程](#-19-篇教程从-hello-world-到多-agent-系统) · [示例代码](./src/test/java/org/salt/jlangchain/demo/) · [提 Issue](https://github.com/flower-trees/j-langchain/issues)

</div>
