# Java 接入多家大模型 API 实战对比

> **适合人群**：需要接入大模型 API 的 Java 开发者，或正在做模型选型的工程师  
> **支持模型**：Ollama（本地）、阿里云通义千问、OpenAI、豆包 Coze

---

## 为什么需要接入多家模型？

- **成本**：不同任务用不同模型，简单任务用便宜模型，复杂任务用高能力模型
- **可用性**：主模型故障时自动降级到备用模型
- **效果**：中文场景用国内模型，代码场景用专业代码模型
- **合规**：部分企业数据不能出境，需要本地部署模型

---

## 支持的模型对比

| 模型 | 供应商 | 特点 | 适用场景 |
|------|--------|------|----------|
| `qwen2.5:0.5b` | Ollama（本地） | 免费、无网络依赖、0延迟 | 开发测试、隐私数据 |
| `qwen-plus` | 阿里云 | 中文效果好、稳定、价格低 | 国内生产环境 |
| `gpt-4` | OpenAI | 能力最强 | 高质量任务 |
| Coze Bot | 字节跳动 | 可自定义知识库和插件 | 企业定制 |

---

## 方式1：本地 Ollama（推荐开发阶段使用）

**优点**：完全免费、数据不出本地、无网络依赖  
**前提**：安装 [Ollama](https://ollama.ai) 并拉取模型

```bash
ollama pull qwen2.5:0.5b   # 轻量版，适合测试
ollama pull llama3:8b       # 8B 参数，效果更好
```

```java
ChatOllama llm = ChatOllama.builder()
    .model("qwen2.5:0.5b")
    // .baseUrl("http://localhost:11434")  // 默认地址，可改为远程 Ollama
    .build();

// 流式调用
AIMessageChunk chunk = llm.stream("用一句话介绍 Java");
while (chunk.getIterator().hasNext()) {
    System.out.print(chunk.getIterator().next().getContent());
}

// 同步调用
AIMessage result = llm.invoke("用一句话介绍 Java");
System.out.println(result.getContent());
```

---

## 方式2：阿里云通义千问

**配置**：

```yaml
# application.yml
spring:
  ai:
    aliyun:
      api-key: ${ALIYUN_KEY}
```

```bash
export ALIYUN_KEY=sk-xxx  # 从阿里云控制台获取
```

```java
ChatAliyun llm = ChatAliyun.builder()
    .model("qwen-plus")    // 可选：qwen-turbo（最快最便宜）/ qwen-plus / qwen-max（最强）
    .build();

AIMessage result = llm.invoke("什么是 Spring Boot？");
System.out.println(result.getContent());
```

**模型选择建议**：

| 模型 | 速度 | 能力 | 价格 |
|------|------|------|------|
| `qwen-turbo` | 最快 | 一般 | 最便宜 |
| `qwen-plus` | 快 | 强 | 中等 |
| `qwen-max` | 慢 | 最强 | 最贵 |

---

## 方式3：模型动态切换

用条件链在运行时选择模型，适合多租户场景（不同用户走不同模型）：

```java
@Test
public void modelSwitcher() {
    ChatOllama freeModel = ChatOllama.builder().model("qwen2.5:0.5b").build();
    ChatAliyun paidModel = ChatAliyun.builder().model("qwen-plus").build();

    FlowInstance chain = chainActor.builder()
        .next(PromptTemplate.fromTemplate("${question}"))
        .next(
            Info.c("tier == 'free'", freeModel),  // 免费用户
            Info.c("tier == 'paid'", paidModel),  // 付费用户
            Info.c(freeModel)                      // 默认
        )
        .next(new StrOutputParser())
        .build();

    // 免费用户
    chainActor.invoke(chain, Map.of("question", "什么是泛型？", "tier", "free"));
    // 付费用户
    chainActor.invoke(chain, Map.of("question", "什么是泛型？", "tier", "paid"));
}
```

---

## 方式4：模型降级（Fallback）

主模型故障时自动切换备用，保障高可用：

```java
@Test
public void modelFallback() {
    ChatAliyun primaryModel = ChatAliyun.builder().model("qwen-plus").build();
    ChatOllama fallbackModel = ChatOllama.builder().model("qwen2.5:0.5b").build();

    String answer;
    try {
        AIMessage result = primaryModel.invoke(question);
        answer = "[主模型] " + result.getContent();
    } catch (Exception e) {
        System.out.println("主模型失败，切换备用：" + e.getMessage());
        AIMessage result = fallbackModel.invoke(question);
        answer = "[备用模型] " + result.getContent();
    }
}
```

---

## 方式5：同一套代码，切换不同模型只需一行

j-langchain 的核心价值：所有模型实现同一套接口（`BaseLLM`），链的构建代码完全一样：

```java
// 只需修改这一行即可切换模型：
ChatOllama llm = ChatOllama.builder().model("qwen2.5:0.5b").build();
// ChatAliyun llm = ChatAliyun.builder().model("qwen-plus").build();
// ChatOpenAI llm = ChatOpenAI.builder().model("gpt-4").build();

// 以下代码完全不变：
FlowInstance chain = chainActor.builder()
    .next(PromptTemplate.fromTemplate("${question}"))
    .next(llm)                     // ← 换模型只改这里
    .next(new StrOutputParser())
    .build();

chainActor.invoke(chain, Map.of("question", "什么是 Java？"));
```

---

## 各模型接入配置速查

### Ollama（本地）
```yaml
# 无需配置，默认 http://localhost:11434
```

### 阿里云通义千问
```yaml
aliyun:
  api-key: ${ALIYUN_KEY}
```

### OpenAI / 兼容 OpenAI 的 API
```yaml
openai:
  api-key: ${OPENAI_KEY}
  base-url: https://api.openai.com/v1  # 或代理地址
```

### 豆包 Coze
```yaml
coze:
  client-id: ${COZE_CLIENT_ID}
  private-key-path: ${COZE_PRIVATE_KEY_PATH}
  public-key-id: ${COZE_PUBLIC_KEY_ID}
```

---

> 完整代码见：`src/test/java/org/salt/jlangchain/demo/article/Article07MultiModel.java`
