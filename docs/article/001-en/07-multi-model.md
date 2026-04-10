# Integrating Multiple LLM APIs in Java: A Practical Comparison

> **Audience**: Java developers who need to integrate LLM APIs, or engineers evaluating model options  
> **Supported models**: Ollama (local), Alibaba Cloud Qwen, OpenAI, ByteDance Coze

---

## Why Use Multiple Models?

- **Cost**: use cheaper models for simple tasks, more capable models for complex ones
- **Availability**: automatically fall back to a backup model when the primary fails
- **Performance**: domestic Chinese models for Chinese-language tasks, specialized code models for coding tasks
- **Compliance**: some enterprise data cannot leave the country — local deployment is required

---

## Supported Model Comparison

| Model | Vendor | Characteristics | Recommended Use |
|-------|--------|----------------|-----------------|
| `qwen2.5:0.5b` | Ollama (local) | Free, no network dependency, zero latency | Development/testing, private data |
| `qwen-plus` | Alibaba Cloud | Great Chinese performance, stable, affordable | Domestic production |
| `gpt-4` | OpenAI | Most capable | High-quality tasks |
| Coze Bot | ByteDance | Customizable knowledge base and plugins | Enterprise customization |

---

## Method 1: Local Ollama (Recommended for Development)

**Advantages**: completely free, data stays local, no network dependency  
**Prerequisite**: install [Ollama](https://ollama.ai) and pull a model

```bash
ollama pull qwen2.5:0.5b   # Lightweight, suitable for testing
ollama pull llama3:8b       # 8B parameters, better quality
```

```java
ChatOllama llm = ChatOllama.builder()
    .model("qwen2.5:0.5b")
    // .baseUrl("http://localhost:11434")  // Default address; can be changed to a remote Ollama
    .build();

// Streaming call
AIMessageChunk chunk = llm.stream("Introduce Java in one sentence");
while (chunk.getIterator().hasNext()) {
    System.out.print(chunk.getIterator().next().getContent());
}

// Synchronous call
AIMessage result = llm.invoke("Introduce Java in one sentence");
System.out.println(result.getContent());
```

---

## Method 2: Alibaba Cloud Qwen

**Configuration**:

```yaml
# application.yml
spring:
  ai:
    aliyun:
      api-key: ${ALIYUN_KEY}
```

```bash
export ALIYUN_KEY=sk-xxx  # Obtain from the Alibaba Cloud console
```

```java
ChatAliyun llm = ChatAliyun.builder()
    .model("qwen-plus")    // Options: qwen-turbo (fastest/cheapest) / qwen-plus / qwen-max (most capable)
    .build();

AIMessage result = llm.invoke("What is Spring Boot?");
System.out.println(result.getContent());
```

**Model selection guide**:

| Model | Speed | Capability | Price |
|-------|-------|-----------|-------|
| `qwen-turbo` | Fastest | Average | Cheapest |
| `qwen-plus` | Fast | Strong | Mid-range |
| `qwen-max` | Slower | Best | Most expensive |

---

## Method 3: Dynamic Model Switching

Use a conditional chain to select a model at runtime — ideal for multi-tenant scenarios where different users use different models:

```java
@Test
public void modelSwitcher() {
    ChatOllama freeModel = ChatOllama.builder().model("qwen2.5:0.5b").build();
    ChatAliyun paidModel = ChatAliyun.builder().model("qwen-plus").build();

    FlowInstance chain = chainActor.builder()
        .next(PromptTemplate.fromTemplate("${question}"))
        .next(
            Info.c("tier == 'free'", freeModel),  // Free-tier users
            Info.c("tier == 'paid'", paidModel),  // Paid users
            Info.c(freeModel)                      // Default
        )
        .next(new StrOutputParser())
        .build();

    // Free-tier user
    chainActor.invoke(chain, Map.of("question", "What are Java generics?", "tier", "free"));
    // Paid user
    chainActor.invoke(chain, Map.of("question", "What are Java generics?", "tier", "paid"));
}
```

---

## Method 4: Model Fallback

Automatically switch to a backup model when the primary model fails, ensuring high availability:

```java
@Test
public void modelFallback() {
    ChatAliyun primaryModel = ChatAliyun.builder().model("qwen-plus").build();
    ChatOllama fallbackModel = ChatOllama.builder().model("qwen2.5:0.5b").build();

    String answer;
    try {
        AIMessage result = primaryModel.invoke(question);
        answer = "[Primary] " + result.getContent();
    } catch (Exception e) {
        System.out.println("Primary model failed, switching to fallback: " + e.getMessage());
        AIMessage result = fallbackModel.invoke(question);
        answer = "[Fallback] " + result.getContent();
    }
}
```

---

## Method 5: Same Code, Switch Models with One Line

The core value of j-langchain: all models implement the same interface (`BaseLLM`), so the chain-building code is completely identical:

```java
// Just change this one line to switch models:
ChatOllama llm = ChatOllama.builder().model("qwen2.5:0.5b").build();
// ChatAliyun llm = ChatAliyun.builder().model("qwen-plus").build();
// ChatOpenAI llm = ChatOpenAI.builder().model("gpt-4").build();

// The rest of the code is completely unchanged:
FlowInstance chain = chainActor.builder()
    .next(PromptTemplate.fromTemplate("${question}"))
    .next(llm)                     // ← only change the model here
    .next(new StrOutputParser())
    .build();

chainActor.invoke(chain, Map.of("question", "What is Java?"));
```

---

## Quick-Reference Configuration for Each Model

### Ollama (local)
```yaml
# No configuration needed; default is http://localhost:11434
```

### Alibaba Cloud Qwen
```yaml
aliyun:
  api-key: ${ALIYUN_KEY}
```

### OpenAI / OpenAI-compatible API
```yaml
openai:
  api-key: ${OPENAI_KEY}
  base-url: https://api.openai.com/v1  # Or a proxy URL
```

### ByteDance Coze
```yaml
coze:
  client-id: ${COZE_CLIENT_ID}
  private-key-path: ${COZE_PRIVATE_KEY_PATH}
  public-key-id: ${COZE_PUBLIC_KEY_ID}
```

---

> Full source code: `src/test/java/org/salt/jlangchain/demo/article/Article07MultiModel.java`
