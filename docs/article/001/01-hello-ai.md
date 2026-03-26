# 5分钟用 Java 构建你的第一个 AI 应用

> **项目地址**：[j-langchain](https://github.com/your-org/j-langchain)  
> **适合人群**：Java 开发者，无需 AI 经验  
> **所需时间**：5 分钟

---

## 为什么是 Java？

目前 AI 应用开发生态几乎被 Python 垄断，LangChain、LlamaIndex 都是 Python 优先。但企业级后端系统大量使用 Java，重写历史服务不现实。

**j-langchain** 是一个纯 Java 实现的 AI 应用开发框架，对标 Python 版 LangChain，让 Java 工程师也能快速构建：

- 基于大模型的问答、摘要、翻译应用
- RAG 知识库问答系统
- 自动化 Agent 工具调用
- 实时流式输出对话

---

## 快速开始

### 1. 添加依赖

```xml
<dependency>
    <groupId>org.salt.jlangchain</groupId>
    <artifactId>j-langchain</artifactId>
    <version>最新版本</version>
</dependency>
```

### 2. 配置大模型 API Key

```yaml
# application.yml
spring:
  ai:
    aliyun:
      api-key: ${ALIYUN_KEY}
```

---

## 核心概念：三步构建 AI 链

j-langchain 最核心的思想是**链式编排**：将 Prompt、LLM、Parser 串联成一个可执行的流水线。

```
输入 → [Prompt 模板] → [大模型] → [输出解析器] → 结果
```

### Step 1：Hello AI — 最简单的三步链

```java
@Test
public void hello() {
    // 1. 定义 Prompt 模板，${topic} 是变量占位符
    PromptTemplate prompt = PromptTemplate.fromTemplate(
        "Tell me a joke about ${topic}"
    );

    // 2. 选择大模型（阿里云通义千问）
    ChatAliyun llm = ChatAliyun.builder()
        .model("qwen-plus")
        .build();

    // 3. 构建调用链：Prompt → LLM → Parser
    FlowInstance chain = chainActor.builder()
        .next(prompt)
        .next(llm)
        .next(new StrOutputParser())
        .build();

    // 4. 执行链，传入变量
    ChatGeneration result = chainActor.invoke(chain, Map.of("topic", "programmers"));

    System.out.println(result.getText());
}
```

三行核心代码：`PromptTemplate` → `ChatAliyun` → `StrOutputParser`，这就是 j-langchain 的最小工作单元。

---

### Step 2：流式输出 — 打字机效果

用户等待 LLM 全部输出完才显示结果，体验很差。流式输出可以边生成边展示：

```java
@Test
public void streamOutput() throws TimeoutException {
    ChatAliyun llm = ChatAliyun.builder().model("qwen-plus").build();

    // stream() 方法返回流式 Chunk 迭代器
    AIMessageChunk chunk = llm.stream("用一句话解释什么是人工智能。");

    StringBuilder sb = new StringBuilder();
    while (chunk.getIterator().hasNext()) {
        String token = chunk.getIterator().next().getContent();
        sb.append(token);
        System.out.print(token); // 逐 token 打印，实现打字机效果
    }
}
```

`stream()` 返回 `AIMessageChunk`，内部是一个阻塞迭代器，每次 `next()` 获取一个 token，直到 LLM 生成完毕。

---

### Step 3：JSON 结构化输出

AI 应用经常需要 LLM 返回结构化数据，而不是纯文本。使用 `JsonOutputParser` 即可：

```java
@Test
public void jsonOutput() {
    ChatAliyun llm = ChatAliyun.builder().model("qwen-plus").build();

    FlowInstance chain = chainActor.builder()
        .next(llm)
        .next(new JsonOutputParser()) // 解析 JSON 格式输出
        .build();

    ChatGeneration result = chainActor.invoke(
        chain,
        "请以 JSON 格式列出3个编程语言，包含 name 和 year 字段。"
    );

    System.out.println(result.getText());
}
```

输出示例：
```json
[
  {"name": "Java", "year": 1995},
  {"name": "Python", "year": 1991},
  {"name": "Go", "year": 2009}
]
```

---

### Step 4：事件流监控 — 调试神器

开发调试时，你可能想知道每个节点的输入输出是什么。`streamEvent()` 能返回链路中每个节点的完整事件：

```java
@Test
public void eventMonitor() throws TimeoutException {
    PromptTemplate prompt = PromptTemplate.fromTemplate("Tell me a joke about ${topic}");
    ChatAliyun llm = ChatAliyun.builder().model("qwen-plus").build();
    FlowInstance chain = chainActor.builder()
        .next(prompt).next(llm).next(new StrOutputParser()).build();

    EventMessageChunk events = chainActor.streamEvent(chain, Map.of("topic", "Java"));

    while (events.getIterator().hasNext()) {
        EventMessageChunk event = events.getIterator().next();
        System.out.println(event.toJson()); // 打印每个节点的事件
    }
}
```

输出的事件包含节点名称、类型（on_chain_start/on_chain_end）、输入输出数据，非常适合排查问题。

---

### Step 5：使用本地模型 — 零成本运行

不想付费调 API？用 Ollama 在本地运行开源模型，完全免费：

```bash
# 安装 Ollama 并拉取模型
ollama pull qwen2.5:0.5b
```

```java
@Test
public void localModel() {
    PromptTemplate prompt = PromptTemplate.fromTemplate("用中文回答：${question}");

    // 替换一行代码即可切换为本地模型，无需 API Key
    ChatOllama llm = ChatOllama.builder().model("qwen2.5:0.5b").build();

    FlowInstance chain = chainActor.builder()
        .next(prompt).next(llm).next(new StrOutputParser()).build();

    ChatGeneration result = chainActor.invoke(
        chain, Map.of("question", "Java 和 Python 有什么区别？")
    );

    System.out.println(result.getText());
}
```

只需将 `ChatAliyun` 替换为 `ChatOllama`，其余代码完全不变——这正是框架抽象层的价值。

---

## 核心 API 总结

| API | 说明 |
|-----|------|
| `PromptTemplate.fromTemplate(str)` | 创建带变量的 Prompt 模板 |
| `ChatAliyun.builder().model(...).build()` | 创建阿里云 LLM 实例 |
| `ChatOllama.builder().model(...).build()` | 创建本地 Ollama LLM 实例 |
| `chainActor.builder().next(...).build()` | 构建链式流水线 |
| `chainActor.invoke(chain, input)` | 同步执行链 |
| `llm.stream(input)` | 流式执行，返回 token 迭代器 |
| `chainActor.streamEvent(chain, input)` | 获取链路事件流（调试用） |

---

## 下一步

- **[文章2]** Java AI 应用的 5 种链式编排模式 — Switch / Compose / Parallel / Route / Dynamic
- **[文章3]** 用 Java 实现 RAG：从 PDF 加载到智能问答全流程
- **[文章4]** Java 实现 ReAct Agent：工具调用与推理循环

---

> 完整代码见：`src/test/java/org/salt/jlangchain/demo/article/Article01HelloAI.java`
