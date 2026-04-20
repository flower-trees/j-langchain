<div align="center">

# J-LangChain

**🚀 Java 平台的企业级 LLM 应用开发框架**

[![Maven Central](https://img.shields.io/maven-central/v/io.github.flower-trees/j-langchain.svg)](https://search.maven.org/artifact/io.github.flower-trees/j-langchain)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](https://opensource.org/licenses/Apache-2.0)
[![Java](https://img.shields.io/badge/Java-17+-green.svg)](https://www.oracle.com/java/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.2+-green.svg)](https://spring.io/projects/spring-boot)

[English](#) | [中文文档](./README_CN.md)

一个强大、灵活、易用的 Java LangChain 实现，让 AI 应用开发像搭积木一样简单

</div>

---

## ✨ 核心特性

### 🎯 多厂商 LLM 支持
开箱即用的主流大模型集成，一套 API 统一调用：
- **OpenAI (ChatGPT)** - GPT-4/GPT-3.5 系列
- **Ollama** - 本地开源模型（Llama3, Qwen2.5, DeepSeek 等）
- **阿里云千问 (DashScope)** - 企业级云端服务
- **Moonshot (Kimi)** - 长上下文支持
- **豆包 (Doubao)** - 字节跳动大模型（火山方舟）
- **扣子 (Coze)** - OAuth 2.0 + SSE 实时推送
- **DeepSeek** - DeepSeek-V3 / DeepSeek-R1 系列
- **腾讯混元 (Hunyuan)** - 腾讯云大模型
- **百度文心 (Qianfan)** - ERNIE 系列
- **智谱 AI (GLM)** - ChatGLM 系列
- **MiniMax** - MiniMax-Text 系列
- **零一万物 (Yi)** - Yi 系列
- **阶跃星辰 (Stepfun)** - Step 系列

### 🔗 灵活的调用链编排
基于 [salt-function-flow](https://github.com/flower-trees/salt-function-flow) 强大的流程编排能力：
- **串行/并行/嵌套** - 多种执行模式随心组合
- **条件路由** - 动态选择执行路径
- **流式输出** - 实时 Token 推送
- **事件监控** - 完整的生命周期追踪

### 📚 完整的 RAG 支持
端到端的检索增强生成能力：
- **文档加载器** - PDF, Word (DOC/DOCX) + OCR 识别
- **文本分割** - 智能分块 + 重叠处理
- **向量嵌入** - OpenAI/Ollama/阿里云多端支持
- **向量数据库** - Milvus 集成 + 相似度搜索

### 🔧 工具调用 & MCP 协议
- **Tool Calling** - 函数调用 + 参数 Schema
- **AgentExecutor** - 封装 ReAct 循环，快速搭建 Agent
- **McpAgentExecutor** - 模型侧 Function Calling + MCP 工具编排
- **@AgentTool / ToolScanner** - 用注解从 Java 方法声明多参数工具
- **MCP (Model Context Protocol)** - 3 种连接方式（Stdio/SSE/HTTP）
- **环境变量配置** - 动态网关路由（或使用 `models.<vendor>.chat-key`）

### 🎤 智能语音合成 (TTS)
- **多厂商支持** - 阿里云/豆包
- **括号内容过滤** - 自动优化 TTS 文本
- **智能分句** - 基于标点的流式生成
- **流式音频** - 实时块级推送

---

## 🚀 5 分钟快速上手

### 1️⃣ 添加依赖

**Maven:**
```xml
<dependency>
    <groupId>io.github.flower-trees</groupId>
    <artifactId>j-langchain</artifactId>
    <version>1.0.14</version>
</dependency>
```

**Gradle:**
```groovy
implementation 'io.github.flower-trees:j-langchain:1.0.14'
```

### 2️⃣ 配置启动类

```java
@SpringBootApplication
@Import(JLangchainConfig.class)  // 导入 J-LangChain 配置
public class Application {
    public static void main(String[] args) {
        SpringApplication.run(Application.class, args);
    }
}
```

### 3️⃣ 设置 API Key

```bash
# LLM 对话模型
export CHATGPT_KEY=sk-xxx...      # OpenAI (ChatGPT) API Key
export ALIYUN_KEY=sk-xxx...      # 阿里云千问 API Key
export MOONSHOT_KEY=sk-xxx...    # Moonshot (Kimi) API Key
export DOUBAO_KEY=sk-xxx...      # 豆包 API Key
export COZE_KEY=xxx...           # 扣子 API Key（或使用下方 OAuth 方式）
export OLLAMA_KEY1=              # Ollama 本地模型通常可留空，网关/代理场景可配置
export DEEPSEEK_KEY=sk-xxx...    # DeepSeek
export HUNYUAN_KEY=sk-xxx...     # 腾讯混元
export QIANFAN_KEY=xxx...       # 百度千帆（文心）
export ZHIPU_KEY=xxx...         # 智谱 AI（GLM）
export MINIMAX_KEY=xxx...       # MiniMax
export LINGYI_KEY=xxx...        # 零一万物（Yi）
export STEPFUN_KEY=xxx...       # 阶跃星辰（Step）

# 扣子 OAuth 2.0 方式（二选一）
# export COZE_CLIENT_ID=xxx
# export COZE_PRIVATE_KEY_PATH=/path/to/private-key.pem
# export COZE_PUBLIC_KEY_ID=xxx

# TTS 语音合成
export ALIYUN_TTS_KEY=xxx...     # 阿里云语音合成
export DOUBAO_TTS_KEY=xxx...     # 豆包语音合成

# 或在 application.yml 中配置 models.xxx.chat-key、tts.xxx.api-key
```

### 4️⃣ 编写第一个 AI 应用

```java
@Component
public class MyFirstAIApp {

    @Autowired
    private ChainActor chainActor;

    public void hello() {
        // 1. 创建 Prompt 模板
        PromptTemplate prompt = PromptTemplate.fromTemplate(
            "Tell me a joke about ${topic}"
        );

        // 2. 选择大模型（阿里云千问）
        ChatAliyun llm = ChatAliyun.builder()
            .model("qwen-plus")
            .build();

        // 3. 构建调用链
        FlowInstance chain = chainActor.builder()
            .next(prompt)
            .next(llm)
            .next(new StrOutputParser())
            .build();

        // 4. 执行并获取结果
        ChatGeneration result = chainActor.invoke(
            chain,
            Map.of("topic", "programmers")
        );

        System.out.println(result.getText());
        // 输出: Why do programmers prefer dark mode?
        //       Because light attracts bugs! 🐛
    }
}
```

✅ **就是这么简单！** 3 行核心代码，完成一个完整的 AI 对话应用。

---

## 💡 核心能力展示

### 🔀 动态路由 - 根据用户输入智能切换模型

```java
ChatOllama ollamaModel = ChatOllama.builder().model("qwen2.5:0.5b").build();
ChatAliyun qwenModel = ChatAliyun.builder().model("qwen-plus").build();

FlowInstance chain = chainActor.builder()
    .next(prompt)
    .next(
        Info.c("vendor == 'ollama'", ollamaModel),
        Info.c("vendor == 'aliyun'", qwenModel),
        Info.c(input -> "Unsupported vendor")
    )
    .next(new StrOutputParser())
    .build();

// 动态选择模型：ollama=本地，aliyun=阿里云千问
chainActor.invoke(chain, Map.of("topic", "AI", "vendor", "aliyun"));
```

### ⚡ 并行执行 - 同时生成笑话和诗歌

```java
ChatOllama llm = ChatOllama.builder().model("qwen2.5:0.5b").build();
BaseRunnable<StringPromptValue, ?> jokePrompt = PromptTemplate.fromTemplate("tell me a joke about ${topic}");
BaseRunnable<StringPromptValue, ?> poemPrompt = PromptTemplate.fromTemplate("write a 2-line poem about ${topic}");

FlowInstance jokeChain = chainActor.builder().next(jokePrompt).next(llm).build();
FlowInstance poemChain = chainActor.builder().next(poemPrompt).next(llm).build();

FlowInstance parallelChain = chainActor.builder()
    .concurrent(jokeChain, poemChain)
    .next(input -> {
        Map<String, Object> map = (Map<String, Object>) input;
        Object jokeObj = map.get(jokeChain.getFlowId());
        Object poemObj = map.get(poemChain.getFlowId());
        String joke = jokeObj instanceof AIMessage ? ((AIMessage) jokeObj).getContent() : String.valueOf(jokeObj);
        String poem = poemObj instanceof AIMessage ? ((AIMessage) poemObj).getContent() : String.valueOf(poemObj);
        return Map.of("joke", joke, "poem", poem);
    })
    .build();

Map<String, String> result = chainActor.invoke(parallelChain, Map.of("topic", "cats"));
System.out.println("Joke: " + result.get("joke"));
System.out.println("Poem: " + result.get("poem"));
```

### 🌊 流式输出 - ChatGPT 打字效果

```java
ChatOllama llm = ChatOllama.builder().model("llama3:8b").build();

AIMessageChunk chunk = llm.stream("What is the meaning of life?");
while (chunk.getIterator().hasNext()) {
    String token = chunk.getIterator().next().getContent();
    System.out.print(token); // 逐字输出
}
```

**输出效果：**
```
The| meaning| of| life| is| to| find| your| purpose|...
```

### 📊 JSON 结构化输出

```java
FlowInstance chain = chainActor.builder()
    .next(llm)
    .next(new JsonOutputParser())  // 自动解析 JSON
    .build();

List<Map> countries = chainActor.stream(chain,
    "List 3 countries with populations in JSON format");
// 返回: [{"country":"China","population":1439323776}, ...]
```

### 🔍 事件流监控 - 调试神器

```java
EventMessageChunk events = chainActor.streamEvent(chain, input);
while (events.getIterator().hasNext()) {
    EventMessageChunk event = events.getIterator().next();
    System.out.println(event.toJson());
}
```

**输出（完整执行链路）：**
```json
{"event":"on_chain_start","data":{"input":{"topic":"AI"}},"name":"ChainActor"}
{"event":"on_prompt_start","data":{"input":{"topic":"AI"}},"name":"PromptTemplate"}
{"event":"on_llm_start","data":{"input":"Tell me about AI"},"name":"ChatOpenAI"}
{"event":"on_llm_stream","data":{"chunk":"Artificial"},"name":"ChatOpenAI"}
{"event":"on_llm_end","data":{"output":"..."},"name":"ChatOpenAI"}
```

### 🎙️ TTS 语音合成（带智能过滤）

```java
AliyunTts tts = AliyunTts.builder()
    .appKey("your-app-key")
    .voice("xiaoyun")
    .format("wav")
    .build();

TtsCardChunk audioChunk = tts.stream("你好(Hello)，欢迎使用 J-LangChain！");
// 自动过滤括号内容，只合成: "你好，欢迎使用 J-LangChain！"
```

---

## 📚 RAG 完整示例

构建一个"文档问答助手"：

```java
// 1. 加载 PDF 文档
PdfboxLoader loader = new PdfboxLoader("path/to/document.pdf");
List<Document> docs = loader.load();

// 2. 文本分割
TextSplitter splitter = StanfordNLPTextSplitter.builder().chunkSize(500).chunkOverlap(50).build();
List<Document> chunks = splitter.splitDocument(docs);

// 3. 向量嵌入
OllamaEmbeddings embeddings = new OllamaEmbeddings();
List<List<Float>> vectors = embeddings.embedDocuments(chunks);

// 4. 存储到向量数据库
Milvus vectorStore = new Milvus(embeddings);
vectorStore.addDocuments(chunks);

// 5. 检索 + 回答
String query = "What is the main topic of the document?";
List<Document> relevantDocs = vectorStore.similaritySearch(query, 3);

ChatPromptTemplate qaPrompt = ChatPromptTemplate.fromMessages(List.of(
    Pair.of("system", "Answer based on: ${context}"),
    Pair.of("human", "${question}")
));

FlowInstance qaChain = chainActor.builder()
    .next(qaPrompt)
    .next(ChatOpenAI.builder().build())
    .next(new StrOutputParser())
    .build();

String answer = chainActor.invoke(qaChain, Map.of(
    "context", relevantDocs.stream()
        .map(Document::getPageContent)
        .collect(Collectors.joining("\n")),
    "question", query
));
```

---

## 🛠️ MCP 工具调用

连接外部工具扩展 LLM 能力：

```java
// 1. 配置 MCP 工具（支持 Stdio/SSE/HTTP）
McpClient mcpClient = new McpClient("path/to/mcp-config.json");

// 2. 获取可用工具
List<ToolDesc> tools = mcpClient.manifest();

// 3. 执行工具
ToolResult result = mcpClient.run("weather_tool", Map.of(
    "city", "Beijing"
));
System.out.println(result.getContent());
// 输出: "Beijing: 25°C, Sunny"

// 4. 与 LLM 结合使用
ChatOpenAI llm = ChatOpenAI.builder()
    .tools(tools)  // 注入工具定义
    .build();

AIMessage response = llm.invoke("What's the weather in Beijing?");
// LLM 自动调用 weather_tool 并返回结果
```

---

## 📖 完整文档

| 文档 | 说明 |
|------|------|
| [快速开始](./docs/guide/quickstart_cn.md) | 详细的入门教程 |
| [API 文档](./docs/api/reference_cn.md) | 完整的 API 参考 |
| [文章教程目录](./docs/article/001/README.md) | 系列教程、阅读顺序、运行环境（Agent / MCP） |
| [MyFirstAIApp](./src/test/java/org/salt/jlangchain/demo/flow/MyFirstAIApp.java) / [示例代码](./src/test/java/org/salt/jlangchain/demo) | 入门示例 + 30+ 实际案例 |
| [最佳实践](./docs/best-practices.md) | 生产环境建议 |

---

## 🏗️ 架构设计

```
┌─────────────────────────────────────────────────────────┐
│                    Application Layer                    │
│          (Your AI Application / Spring Boot)            │
└─────────────────────────────────────────────────────────┘
                           ▼
┌─────────────────────────────────────────────────────────┐
│                   J-LangChain Core                      │
│  ┌─────────────┬──────────────┬──────────────────────┐  │
│  │ ChainActor  │ PromptTpl    │ OutputParser         │  │
│  │ (Orchestr.) │ (Templates)  │ (Str/JSON/Function)  │  │
│  └─────────────┴──────────────┴──────────────────────┘  │
└─────────────────────────────────────────────────────────┘
                           ▼
┌───────────────┬──────────────┬─────────────┬───────────┐
│  LLM (13+)    │     RAG      │     TTS     │    MCP    │
│ Multi-vendor  │ Loader/Split │ Aliyun/Doubao│ Tools     │
│ chat + tools  │ Embed/Vector │ Smart Filter│ Stdio/SSE │
│ domestic+intl │ Milvus Store │ Streaming   │ HTTP + FC │
└───────────────┴──────────────┴─────────────┴───────────┘
```

---

## 🌟 为什么选择 J-LangChain？

### 🆚 对比其他方案

| 特性 | J-LangChain | LangChain4j | Spring AI |
|------|-------------|-------------|-----------|
| **多厂商支持** | ✅ 13+ (含国内) | ✅ 5+ | ⚠️ 主流 |
| **流程编排** | ✅ 强大的 salt-flow | ⚠️ 链式调用 | ⚠️ 基础 |
| **RAG 完整性** | ✅ 端到端 | ✅ 完整 | ⚠️ 部分 |
| **TTS 支持** | ✅ 智能优化 | ❌ | ❌ |
| **MCP 协议** | ✅ 3 种连接 + Function Calling / Agent | ❌ | ❌ |
| **事件监控** | ✅ 完整生命周期 | ⚠️ 基础 | ❌ |
| **Spring Boot** | ✅ 原生支持 | ✅ | ✅ |
| **学习曲线** | 🟢 简单 | 🟡 中等 | 🟢 简单 |

### 💪 核心优势

1. **🇨🇳 国内大模型优先** - 深度集成阿里云、豆包、Kimi 等
2. **🔄 强大的编排能力** - 基于 salt-function-flow 的工作流引擎
3. **📦 开箱即用** - Spring Boot 自动配置，零配置启动
4. **🎯 生产级设计** - 事件追踪、错误处理、性能优化
5. **🔧 高度可扩展** - 清晰的抽象层，易于添加新厂商

---

## 🤝 参与贡献

我们欢迎各种形式的贡献！

- 🐛 [提交 Bug](https://github.com/flower-trees/j-langchain/issues)
- 💡 [功能建议](https://github.com/flower-trees/j-langchain/issues)
- 📝 改进文档
- 🔧 提交 Pull Request

### 贡献步骤
```bash
# 1. Fork 项目
# 2. 创建特性分支
git checkout -b feature/amazing-feature

# 3. 提交更改
git commit -m 'Add amazing feature'

# 4. 推送分支
git push origin feature/amazing-feature

# 5. 创建 Pull Request
```

---

## 📄 开源协议

本项目采用 [Apache License 2.0](./LICENSE) 开源协议。

---

## 🙏 致谢

- [LangChain](https://github.com/langchain-ai/langchain) - 灵感来源
- [salt-function-flow](https://github.com/flower-trees/salt-function-flow) - 流程编排引擎
- [Spring Boot](https://spring.io/projects/spring-boot) - 应用框架

---

## 📞 联系我们

- 📧 Email: huaqianshu_z@163.com
- 🐛 Issues: [GitHub Issues](https://github.com/flower-trees/j-langchain/issues)
- 💬 Discussions: [GitHub Discussions](https://github.com/flower-trees/j-langchain/discussions)

---

<div align="center">

**⭐ 如果这个项目对你有帮助，请给我们一个 Star！**

Made with ❤️ by J-LangChain Team

[快速开始](./docs/guide/quickstart_cn.md) • [MyFirstAIApp](./src/test/java/org/salt/jlangchain/demo/flow/MyFirstAIApp.java) • [示例代码](./src/test/java/org/salt/jlangchain/demo/) • [API 文档](./docs/api/reference_cn.md)

</div>
