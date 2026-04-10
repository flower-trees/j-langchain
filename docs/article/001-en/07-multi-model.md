# Integrating Multiple LLM APIs in Java

> **Audience**: Java engineers evaluating/connecting different LLM vendors  
> **Supported models**: Ollama (local), Aliyun Qwen, OpenAI, ByteDance Coze

---

## Why Use Multiple Models?

- **Cost**: run inexpensive models for simple tasks and premium ones for complex jobs.
- **Availability**: fail over to a backup when the primary model has incidents.
- **Quality**: pick domestic models for Chinese, specialized code models for programming help.
- **Compliance**: some data must stay on-premises, so you need a local model.

---

## Supported Models

| Model | Vendor | Highlights | Scenarios |
|-------|--------|------------|-----------|
| `qwen2.5:0.5b` | Ollama | Free, offline, local latency | Dev/testing, private data |
| `qwen-plus` | Aliyun | Strong Chinese quality, stable, low price | Prod workloads in China |
| `gpt-4` | OpenAI | Highest capability | Premium tasks |
| Coze Bot | ByteDance | Custom KB + plugins | Enterprise customization |

---

## Option 1: Local Ollama

Install [Ollama](https://ollama.ai) and pull a model:

```bash
ollama pull qwen2.5:0.5b
ollama pull llama3:8b
```

```java
ChatOllama llm = ChatOllama.builder()
    .model("qwen2.5:0.5b")
    .build();

AIMessageChunk chunk = llm.stream("用一句话介绍 Java");
while (chunk.getIterator().hasNext()) {
    System.out.print(chunk.getIterator().next().getContent());
}

AIMessage result = llm.invoke("用一句话介绍 Java");
System.out.println(result.getContent());
```

---

## Option 2: Aliyun Qwen

```yaml
spring:
  ai:
    aliyun:
      api-key: ${ALIYUN_KEY}
```

```bash
export ALIYUN_KEY=sk-xxx
```

```java
ChatAliyun llm = ChatAliyun.builder()
    .model("qwen-plus")
    .build();

AIMessage result = llm.invoke("什么是 Spring Boot？");
System.out.println(result.getContent());
```

| Model | Speed | Capability | Cost |
|-------|-------|------------|------|
| `qwen-turbo` | Fastest | Decent | Cheapest |
| `qwen-plus` | Fast | Strong | Medium |
| `qwen-max` | Slow | Strongest | Highest |

---

## Option 3: Runtime Switching

Use a switch chain to pick models per tenant:

```java
@Test
public void modelSwitcher() {
    ChatOllama freeModel = ChatOllama.builder().model("qwen2.5:0.5b").build();
    ChatAliyun paidModel = ChatAliyun.builder().model("qwen-plus").build();

    FlowInstance chain = chainActor.builder()
        .next(PromptTemplate.fromTemplate("${question}"))
        .next(
            Info.c("tier == 'free'", freeModel),
            Info.c("tier == 'paid'", paidModel),
            Info.c(freeModel)
        )
        .next(new StrOutputParser())
        .build();

    chainActor.invoke(chain, Map.of("question", "什么是泛型？", "tier", "free"));
    chainActor.invoke(chain, Map.of("question", "什么是泛型？", "tier", "paid"));
}
```

---

## Option 4: Fallback

Automatically downgrade when the primary model fails:

```java
@Test
public void modelFallback() {
    ChatAliyun primaryModel = ChatAliyun.builder().model("qwen-plus").build();
    ChatOllama fallbackModel = ChatOllama.builder().model("qwen2.5:0.5b").build();

    String answer;
    try {
        AIMessage result = primaryModel.invoke(question);
        answer = "[primary] " + result.getContent();
    } catch (Exception e) {
        AIMessage result = fallbackModel.invoke(question);
        answer = "[fallback] " + result.getContent();
    }
}
```

---

## Option 5: One-Line Swaps

Every LLM implements the same `BaseLLM` interface, so swapping is one line:

```java
ChatOllama llm = ChatOllama.builder().model("qwen2.5:0.5b").build();
// ChatAliyun llm = ChatAliyun.builder().model("qwen-plus").build();
// ChatOpenAI llm = ChatOpenAI.builder().model("gpt-4").build();

FlowInstance chain = chainActor.builder()
    .next(PromptTemplate.fromTemplate("${question}"))
    .next(llm)
    .next(new StrOutputParser())
    .build();

chainActor.invoke(chain, Map.of("question", "什么是 Java？"));
```

---

## Config Cheatsheet

- **Ollama**: no config if running on `http://localhost:11434`
- **Aliyun Qwen**
  ```yaml
  aliyun:
    api-key: ${ALIYUN_KEY}
  ```
- **OpenAI-compatible APIs**
  ```yaml
  openai:
    api-key: ${OPENAI_KEY}
    base-url: https://api.openai.com/v1
  ```
- **Coze**
  ```yaml
  coze:
    client-id: ${COZE_CLIENT_ID}
    private-key-path: ${COZE_PRIVATE_KEY_PATH}
    public-key-id: ${COZE_PUBLIC_KEY_ID}
  ```

---

> Full code: `src/test/java/org/salt/jlangchain/demo/article/Article07MultiModel.java`
