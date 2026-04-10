# Build Your First AI App with Java in 5 Minutes

> **Project**: [j-langchain](https://github.com/your-org/j-langchain)  
> **Audience**: Java developers, no AI experience required  
> **Time**: 5 minutes

---

## Why Java?

The AI application ecosystem is almost entirely dominated by Python — LangChain and LlamaIndex are Python-first. But enterprise backend systems heavily rely on Java, and rewriting legacy services is not realistic.

**j-langchain** is a pure Java AI application framework that mirrors Python's LangChain, letting Java engineers quickly build:

- Q&A, summarization, and translation apps powered by large models
- RAG knowledge-base Q&A systems
- Automated Agent tool-calling pipelines
- Real-time streaming chat

---

## Quick Start

### 1. Add the Dependency

```xml
<dependency>
    <groupId>org.salt.jlangchain</groupId>
    <artifactId>j-langchain</artifactId>
    <version>latest</version>
</dependency>
```

### 2. Configure the LLM API Key

```yaml
# application.yml
spring:
  ai:
    aliyun:
      api-key: ${ALIYUN_KEY}
```

---

## Core Concept: Build an AI Chain in Three Steps

The central idea of j-langchain is **chain orchestration**: connecting a Prompt, an LLM, and a Parser into an executable pipeline.

```
Input → [Prompt Template] → [LLM] → [Output Parser] → Result
```

### Step 1: Hello AI — The Simplest Three-Step Chain

```java
@Test
public void hello() {
    // 1. Define a Prompt template; ${topic} is a variable placeholder
    PromptTemplate prompt = PromptTemplate.fromTemplate(
        "Tell me a joke about ${topic}"
    );

    // 2. Choose an LLM (Alibaba Cloud Qwen)
    ChatAliyun llm = ChatAliyun.builder()
        .model("qwen-plus")
        .build();

    // 3. Build the chain: Prompt → LLM → Parser
    FlowInstance chain = chainActor.builder()
        .next(prompt)
        .next(llm)
        .next(new StrOutputParser())
        .build();

    // 4. Execute the chain with variables
    ChatGeneration result = chainActor.invoke(chain, Map.of("topic", "programmers"));

    System.out.println(result.getText());
}
```

Three core lines: `PromptTemplate` → `ChatAliyun` → `StrOutputParser`. This is the minimal working unit of j-langchain.

---

### Step 2: Streaming Output — Typewriter Effect

Waiting for the LLM to finish generating before displaying anything leads to a poor experience. Streaming lets you show output as it is generated:

```java
@Test
public void streamOutput() throws TimeoutException {
    ChatAliyun llm = ChatAliyun.builder().model("qwen-plus").build();

    // stream() returns a streaming chunk iterator
    AIMessageChunk chunk = llm.stream("Explain what artificial intelligence is in one sentence.");

    StringBuilder sb = new StringBuilder();
    while (chunk.getIterator().hasNext()) {
        String token = chunk.getIterator().next().getContent();
        sb.append(token);
        System.out.print(token); // Print token by token for the typewriter effect
    }
}
```

`stream()` returns an `AIMessageChunk` with a blocking iterator. Each call to `next()` retrieves one token until the LLM finishes.

---

### Step 3: JSON Structured Output

AI applications often need structured data from the LLM rather than plain text. Use `JsonOutputParser`:

```java
@Test
public void jsonOutput() {
    ChatAliyun llm = ChatAliyun.builder().model("qwen-plus").build();

    FlowInstance chain = chainActor.builder()
        .next(llm)
        .next(new JsonOutputParser()) // Parse JSON-formatted output
        .build();

    ChatGeneration result = chainActor.invoke(
        chain,
        "List 3 programming languages in JSON format with 'name' and 'year' fields."
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

### Step 4: Event Stream Monitoring — Debug Tool

During development you may want to see the input and output at each node. `streamEvent()` returns the full event stream for every node in the chain:

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
        System.out.println(event.toJson()); // Print each node's event
    }
}
```

Each event includes the node name, type (`on_chain_start` / `on_chain_end`), and input/output data — ideal for debugging.

---

### Step 5: Local Model — Zero Cost

Don't want to pay for an API? Run an open-source model locally with Ollama, completely free:

```bash
# Install Ollama and pull a model
ollama pull qwen2.5:0.5b
```

```java
@Test
public void localModel() {
    PromptTemplate prompt = PromptTemplate.fromTemplate("Answer in English: ${question}");

    // Just replace one line to switch to a local model — no API key needed
    ChatOllama llm = ChatOllama.builder().model("qwen2.5:0.5b").build();

    FlowInstance chain = chainActor.builder()
        .next(prompt).next(llm).next(new StrOutputParser()).build();

    ChatGeneration result = chainActor.invoke(
        chain, Map.of("question", "What is the difference between Java and Python?")
    );

    System.out.println(result.getText());
}
```

Simply replace `ChatAliyun` with `ChatOllama` and the rest of the code stays exactly the same — that is the value of the framework's abstraction layer.

---

## Core API Summary

| API | Description |
|-----|-------------|
| `PromptTemplate.fromTemplate(str)` | Create a Prompt template with variables |
| `ChatAliyun.builder().model(...).build()` | Create an Alibaba Cloud LLM instance |
| `ChatOllama.builder().model(...).build()` | Create a local Ollama LLM instance |
| `chainActor.builder().next(...).build()` | Build a chain pipeline |
| `chainActor.invoke(chain, input)` | Execute the chain synchronously |
| `llm.stream(input)` | Streaming execution, returns a token iterator |
| `chainActor.streamEvent(chain, input)` | Get the chain event stream (for debugging) |

---

## Next Steps

- **[Article 2]** 5 Chain Orchestration Patterns for Java AI Apps — Switch / Compose / Parallel / Route / Dynamic
- **[Article 3]** Implementing RAG in Java: From PDF Loading to Intelligent Q&A
- **[Article 4]** Implementing a ReAct Agent in Java: Tool Calling and Reasoning Loops

---

> Full code: [`src/test/java/org/salt/jlangchain/demo/article/Article01HelloAI.java`](/src/test/java/org/salt/jlangchain/demo/article/Article01HelloAI.java)

---

## Recap

- Add `PromptTemplate` + `LLM` + `Parser` to build a chain in three lines.
- Use `stream()` for token streaming and `streamEvent()` for debugging.
- Choose a parser (`StrOutputParser`, `JsonOutputParser`, or a custom one) to enforce response formats.

With that, you now have a runnable AI app in pure Java. Next up: explore more orchestration patterns in [Article 02](02-chain-patterns.md).
