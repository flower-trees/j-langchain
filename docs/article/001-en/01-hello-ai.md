# Build Your First AI App in Java in Five Minutes

> **Repository**: [j-langchain](https://github.com/your-org/j-langchain)  
> **Audience**: Java developers with zero AI background  
> **Estimated time**: 5 minutes

---

## Why Java?

The AI application ecosystem today is almost entirely dominated by Python—frameworks such as LangChain and LlamaIndex are Python-first. Yet most enterprise backends are written in Java, and rewriting existing services is unrealistic.

**j-langchain** is a pure-Java AI application framework, mirroring the Python LangChain API so Java engineers can quickly build:

- Q&A, summarization, and translation features powered by large language models
- RAG knowledge-base assistants
- Automated agents that call tools
- Real-time streaming chat experiences

---

## Quick Start

### 1. Add the dependency

```xml
<dependency>
    <groupId>org.salt.jlangchain</groupId>
    <artifactId>j-langchain</artifactId>
    <version>latest</version>
</dependency>
```

### 2. Configure an LLM API key

```yaml
# application.yml
spring:
  ai:
    aliyun:
      api-key: ${ALIYUN_KEY}
```

---

## Core Idea: Build a Three-Step Chain

The heart of j-langchain is **chain orchestration**: wire a prompt, an LLM, and a parser into an executable pipeline.

```
Input → [Prompt template] → [LLM] → [Output parser] → Result
```

### Step 1: Hello AI — the simplest chain

```java
@Test
public void hello() {
    // 1. Prompt template, ${topic} is a placeholder
    PromptTemplate prompt = PromptTemplate.fromTemplate(
        "Tell me a joke about ${topic}"
    );

    // 2. Pick an LLM (Qwen from Aliyun)
    ChatAliyun llm = ChatAliyun.builder()
        .model("qwen-plus")
        .build();

    // 3. Chain Prompt → LLM → Parser
    FlowInstance chain = chainActor.builder()
        .next(prompt)
        .next(llm)
        .next(new StrOutputParser())
        .build();

    // 4. Execute with variables
    ChatGeneration result = chainActor.invoke(chain, Map.of("topic", "programmers"));

    System.out.println(result.getText());
}
```

Three key components—`PromptTemplate`, `ChatAliyun`, `StrOutputParser`—form the minimal unit in j-langchain.

---

### Step 2: Streaming output — the typewriter effect

Waiting for the LLM to finish before showing text feels slow. Streaming lets you display tokens as they arrive:

```java
@Test
public void streamOutput() throws TimeoutException {
    ChatAliyun llm = ChatAliyun.builder().model("qwen-plus").build();

    // stream() returns an iterator of chunks
    AIMessageChunk chunk = llm.stream("用一句话解释什么是人工智能。");

    StringBuilder sb = new StringBuilder();
    while (chunk.getIterator().hasNext()) {
        String token = chunk.getIterator().next().getContent();
        sb.append(token);
        System.out.print(token); // print token by token
    }
}
```

`stream()` yields an `AIMessageChunk` that wraps a blocking iterator. Each `next()` call pulls one token until the model stops generating.

---

### Step 3: Structured JSON output

Many applications want structured data instead of plain text. Use `JsonOutputParser` to enforce JSON:

```java
@Test
public void jsonOutput() {
    ChatAliyun llm = ChatAliyun.builder().model("qwen-plus").build();

    FlowInstance chain = chainActor.builder()
        .next(llm)
        .next(new JsonOutputParser())
        .build();

    ChatGeneration result = chainActor.invoke(
        chain,
        "请以 JSON 格式列出3个编程语言，包含 name 和 year 字段。"
    );

    System.out.println(result.getText());
}
```

Sample output:

```json
[
  {"name": "Java", "year": 1995},
  {"name": "Python", "year": 1991},
  {"name": "Go", "year": 2009}
]
```

---

### Step 4: Event streaming for debugging

During development you may want to see every node’s input and output. `streamEvent()` returns the full chain events:

```java
@Test
public void eventMonitor() throws TimeoutException {
    PromptTemplate prompt = PromptTemplate.fromTemplate("Tell me a joke about ${topic}");
    ChatAliyun llm = ChatAliyun.builder().model("qwen-plus").build();
    FlowInstance chain = chainActor.builder()
        .next(prompt).next(llm).next(new StrOutputParser()).build();

    EventMessageChunk events = chainActor.streamEvent(chain, Map.of("topic", "Java"));

    while (events.getIterator().hasNext()) {
        EventMessageChunk.Event event = events.getIterator().next();
        System.out.printf("node=%s type=%s input=%s output=%s\n",
            event.getNodeId(), event.getEventName(), event.getInput(), event.getOutput());
    }
}
```

You will receive events for every node, including execution time, which is invaluable when prompts misbehave.

---

### Step 5: Run local models for zero cost

No budget for API calls? Run open-source models locally with Ollama for free:

```bash
# Install Ollama and pull a model
ollama pull qwen2.5:0.5b
```

```java
@Test
public void localModel() {
    PromptTemplate prompt = PromptTemplate.fromTemplate(\"用中文回答：${question}\");

    // Switch to a local model by replacing one line, no API key required
    ChatOllama llm = ChatOllama.builder().model(\"qwen2.5:0.5b\").build();

    FlowInstance chain = chainActor.builder()
        .next(prompt).next(llm).next(new StrOutputParser()).build();

    ChatGeneration result = chainActor.invoke(
        chain, Map.of(\"question\", \"Java 和 Python 有什么区别？\")
    );

    System.out.println(result.getText());
}
```

Replace `ChatAliyun` with `ChatOllama` and everything else stays the same—that’s the power of the abstraction layer.

---

## Key APIs Recap

| API | Description |
|-----|-------------|
| `PromptTemplate.fromTemplate(str)` | Create a prompt template with variables |
| `ChatAliyun.builder().model(...).build()` | Create an Aliyun LLM instance |
| `ChatOllama.builder().model(...).build()` | Create a local Ollama LLM instance |
| `chainActor.builder().next(...).build()` | Build a chained pipeline |
| `chainActor.invoke(chain, input)` | Execute synchronously |
| `llm.stream(input)` | Stream tokens from the LLM |
| `chainActor.streamEvent(chain, input)` | Fetch chain events for debugging |

---

## What’s next

- **[Article 02]** Five orchestration patterns in Java AI apps—switch/compose/parallel/route/dynamic chains\n- **[Article 03]** Java RAG pipeline: from PDF ingestion to intelligent Q&A\n- **[Article 04]** ReAct agents in Java: tool invocation and reasoning loops

---

> Full code: `src/test/java/org/salt/jlangchain/demo/article/Article01HelloAI.java`

---

## Recap

- Add `PromptTemplate` + `LLM` + `Parser` to build a chain in three lines.
- Use `stream()` for token streaming and `streamEvent()` for debugging.
- Choose a parser (`StrOutputParser`, `JsonOutputParser`, or a custom one) to enforce response formats.

With that, you now have a runnable AI app in pure Java. Next up: explore more orchestration patterns in [Article 02](02-chain-patterns.md).
