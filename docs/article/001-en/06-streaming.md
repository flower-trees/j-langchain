# Streaming Output in Java AI Applications

> **Audience**: Java backend engineers who need real-time, typewriter-style output  
> **Core APIs**: `stream()`, `streamEvent()`, `stop()`

---

## Why Streaming Matters

**Synchronous**: wait for the LLM to finish before showing text → 5–30 seconds of silence.  
**Streaming**: push every token the moment it is generated → perceived latency <1 second.

Every mainstream chat UI (ChatGPT, Claude, Qwen) streams tokens. It’s the default experience for AI chat.

---

## How Streaming Works

```
LLM emits: [你] [好] [，] [我] [是] [AI] [助] [手]
Front-end shows: 你 → 你好 → 你好， → ...
```

At the HTTP layer it’s SSE or WebSocket; j-langchain wraps it as a blocking iterator so you don’t deal with protocols.

---

## Mode 1: Stream Directly from the LLM

```java
@Test
public void basicStream() throws TimeoutException {
    ChatOllama llm = ChatOllama.builder().model("qwen2.5:0.5b").build();
    AIMessageChunk chunk = llm.stream("天空是什么颜色？");
    while (chunk.getIterator().hasNext()) {
        String token = chunk.getIterator().next().getContent();
        System.out.print(token);
    }
}
```

`AIMessageChunk.getIterator()` blocks until new tokens arrive or the stream ends.

---

## Mode 2: Stream an Entire Chain

```java
@Test
public void chainStream() throws TimeoutException {
    FlowInstance chain = chainActor.builder()
        .next(PromptTemplate.fromTemplate("讲一个关于 ${topic} 的笑话"))
        .next(ChatOllama.builder().model("qwen2.5:0.5b").build())
        .next(new StrOutputParser())
        .build();

    ChatGenerationChunk chunk = chainActor.stream(chain, Map.of("topic", "程序员"));
    while (chunk.getIterator().hasNext()) {
        System.out.print(chunk.getIterator().next().getText());
    }
}
```

| | `invoke()` | `stream()` |
|---|-----------|-----------|
| Return timing | After completion | Immediately |
| Type | `ChatGeneration` | `ChatGenerationChunk` |
| Scenarios | Batch/offline | Interactive UI |

---

## Mode 3: Cancel Mid-Stream

```java
@Test
public void streamWithStop() throws TimeoutException {
    FlowInstance chain = chainActor.builder()
        .next(prompt).next(llm).next(new StrOutputParser()).build();

    ChatGenerationChunk chunk = chainActor.stream(chain, Map.of("topic", "太空探索"));

    int tokenCount = 0;
    while (chunk.getIterator().hasNext()) {
        System.out.print(chunk.getIterator().next().getText());
        if (++tokenCount >= 5) {
            chainActor.stop(chain);
            System.out.println("\n[stopped]");
            break;
        }
    }
}
```

`chainActor.stop(chain)` sends a cancel signal, closes the stream, and `hasNext()` becomes `false`.

---

## Mode 4: Stream JSON

```java
@Test
public void jsonStream() throws TimeoutException {
    FlowInstance chain = chainActor.builder()
        .next(ChatOllama.builder().model("qwen2.5:0.5b").build())
        .next(new JsonOutputParser())
        .build();

    ChatGenerationChunk chunk = chainActor.stream(
        chain, "以 JSON 格式输出3个国家及其人口"
    );

    while (chunk.getIterator().hasNext()) {
        System.out.println(chunk.getIterator().next());
    }
}
```

---

## Mode 5: Event Streams for Debugging

`streamEvent()` yields execution events (`on_chain_start`, `on_llm_stream`, `on_chain_end`, …):

```java
@Test
public void eventStream() throws TimeoutException {
    FlowInstance chain = chainActor.builder()
        .next(prompt).next(llm).next(new StrOutputParser()).build();

    EventMessageChunk events = chainActor.streamEvent(chain, Map.of("topic", "狗"));

    while (events.getIterator().hasNext()) {
        EventMessageChunk event = events.getIterator().next();
        System.out.println(event.toJson());
    }
}
```

---

## Mode 6: Filter Events

Tag nodes and filter by name/type/tag:

```java
FlowInstance chain = chainActor.builder()
    .next(llm.withConfig(Map.of("run_name", "my_llm")))
    .next(parser.withConfig(Map.of("run_name", "my_parser", "tags", List.of("my_chain"))))
    .build();

EventMessageChunk byName = chainActor.streamEvent(
    chain, input,
    event -> List.of("my_parser").contains(event.getName())
);

EventMessageChunk byType = chainActor.streamEvent(
    chain, input,
    event -> List.of("llm").contains(event.getType())
);

EventMessageChunk byTag = chainActor.streamEvent(
    chain, input,
    event -> event.getTags() != null && event.getTags().contains("my_chain")
);
```

---

## Push Streams in Spring Boot

### SSE (Recommended)

```java
@GetMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
public SseEmitter streamChat(@RequestParam String question) {
    SseEmitter emitter = new SseEmitter(30_000L);

    CompletableFuture.runAsync(() -> {
        try {
            FlowInstance chain = buildChain();
            ChatGenerationChunk chunk = chainActor.stream(chain, question);
            while (chunk.getIterator().hasNext()) {
                String token = chunk.getIterator().next().getText();
                emitter.send(SseEmitter.event().data(token));
            }
            emitter.send(SseEmitter.event().name("done").data("[DONE]"));
            emitter.complete();
        } catch (Exception e) {
            emitter.completeWithError(e);
        }
    });

    return emitter;
}
```

```javascript
const eventSource = new EventSource('/chat/stream?question=你好');
eventSource.onmessage = (e) => {
  document.getElementById('output').textContent += e.data;
};
eventSource.addEventListener('done', () => eventSource.close());
```

---

## Streaming API Summary

| Method | Return | Scenario |
|--------|--------|----------|
| `llm.stream(input)` | `AIMessageChunk` | Direct LLM streaming |
| `chainActor.stream(chain, input)` | `ChatGenerationChunk` | Chain streaming |
| `chainActor.streamEvent(chain, input)` | `EventMessageChunk` | Debug/monitor |
| `chainActor.streamEvent(..., filter)` | `EventMessageChunk` | Filtered events |
| `chainActor.stop(chain)` | `void` | Cancel generation |

---

> Full code: `src/test/java/org/salt/jlangchain/demo/article/Article06Streaming.java`
