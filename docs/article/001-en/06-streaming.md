# Streaming Output in Java AI Applications: From Principles to Practice

> **Audience**: Java backend engineers who need to implement typewriter-style, real-time conversational output  
> **Core APIs**: `stream()`, `streamEvent()`, `stop()`

---

## Why Is Streaming Output So Important?

**Synchronous mode**: wait for the LLM to finish generating all content before showing anything → users wait 5–30 seconds, terrible experience  
**Streaming mode**: push each token to the user as soon as it is generated → users see a "typewriter" effect, perceived latency drops to under 1 second

ChatGPT, Claude, and Qwen all use streaming output in their chat interfaces. It is the **standard technology** for AI chat products.

---

## How Streaming Output Works

```
LLM generates: [H][e][l][l][o][,][ ][I][ ][a][m][ ][A][I]
                   ↓ push each token as it is generated
Frontend receives: H → He → Hel → Hell → Hello → Hello, → Hello, I → ...
```

At the HTTP layer, either **SSE (Server-Sent Events)** or **WebSocket** is used. j-langchain wraps this with a **blocking iterator** at the Java level, so developers do not need to think about the underlying protocol.

---

## Method 1: Direct LLM Streaming

The most basic usage — call `llm.stream()` directly:

```java
@Test
public void basicStream() throws TimeoutException {
    ChatOllama llm = ChatOllama.builder().model("qwen2.5:0.5b").build();

    // stream() returns immediately without waiting for the LLM to finish
    AIMessageChunk chunk = llm.stream("What color is the sky?");

    while (chunk.getIterator().hasNext()) {
        String token = chunk.getIterator().next().getContent();
        System.out.print(token);  // Print token by token
    }
}
```

`AIMessageChunk.getIterator()` is a **blocking iterator**:
- `hasNext()` blocks until the next token arrives or generation ends
- `next()` returns the next token's `AIMessageChunk`
- After generation ends, `hasNext()` returns `false`

---

## Method 2: Chain Streaming

The entire chain (Prompt → LLM → Parser) streams:

```java
@Test
public void chainStream() throws TimeoutException {
    FlowInstance chain = chainActor.builder()
        .next(PromptTemplate.fromTemplate("Tell a joke about ${topic}"))
        .next(ChatOllama.builder().model("qwen2.5:0.5b").build())
        .next(new StrOutputParser())
        .build();

    // chainActor.stream() returns a ChatGenerationChunk
    ChatGenerationChunk chunk = chainActor.stream(chain, Map.of("topic", "programmers"));

    while (chunk.getIterator().hasNext()) {
        System.out.print(chunk.getIterator().next().getText());
    }
}
```

**`invoke` vs `stream`**:

| | `invoke()` | `stream()` |
|--|-----------|-----------|
| When it returns | After full generation | Immediately; iterator lazy-loads |
| Return type | `ChatGeneration` | `ChatGenerationChunk` |
| Use cases | Background tasks, batch processing | Real-time chat, UI interactions |

---

## Method 3: Cancel Streaming Mid-way

When the user clicks "stop generating", you can interrupt immediately:

```java
@Test
public void streamWithStop() throws TimeoutException {
    FlowInstance chain = chainActor.builder()
        .next(prompt).next(llm).next(new StrOutputParser()).build();

    ChatGenerationChunk chunk = chainActor.stream(chain, Map.of("topic", "space exploration"));

    int tokenCount = 0;
    while (chunk.getIterator().hasNext()) {
        System.out.print(chunk.getIterator().next().getText());
        tokenCount++;

        if (tokenCount >= 5) {
            chainActor.stop(chain);  // Stop immediately, release resources
            System.out.println("\n[Stopped]");
            break;
        }
    }
}
```

`chainActor.stop(chain)` will:
1. Send a cancellation signal to the LLM
2. Clean up the streaming connection
3. Cause subsequent `hasNext()` calls to return `false`

---

## Method 4: Streaming JSON Output

The LLM generates JSON token by token, and `JsonOutputParser` parses it incrementally, returning the current JSON state at each step:

```java
@Test
public void jsonStream() throws TimeoutException {
    FlowInstance chain = chainActor.builder()
        .next(ChatOllama.builder().model("qwen2.5:0.5b").build())
        .next(new JsonOutputParser())  // Streaming JSON parsing
        .build();

    ChatGenerationChunk chunk = chainActor.stream(
        chain, "Output 3 countries and their populations in JSON format"
    );

    while (chunk.getIterator().hasNext()) {
        System.out.println(chunk.getIterator().next());
        // Each output is the partially parsed JSON so far
    }
}
```

---

## Method 5: Event Stream — Debugging Tool

`streamEvent()` returns **execution events** for every node in the chain, including:
- `on_chain_start`: node starts executing
- `on_llm_stream`: LLM streaming token
- `on_chain_end`: node finishes executing

```java
@Test
public void eventStream() throws TimeoutException {
    FlowInstance chain = chainActor.builder()
        .next(prompt).next(llm).next(new StrOutputParser()).build();

    EventMessageChunk events = chainActor.streamEvent(chain, Map.of("topic", "dogs"));

    while (events.getIterator().hasNext()) {
        EventMessageChunk event = events.getIterator().next();
        System.out.println(event.toJson());
        // {"type": "llm", "name": "ChatOllama", "event": "on_llm_stream", "data": {...}}
    }
}
```

---

## Method 6: Filtered Event Stream

When there are too many events, filter by name, type, or tags to focus on what you care about:

```java
// Assign names and tags to nodes
FlowInstance chain = chainActor.builder()
    .next(llm.withConfig(Map.of("run_name", "my_llm")))
    .next(parser.withConfig(Map.of("run_name", "my_parser", "tags", List.of("my_chain"))))
    .build();

// Filter by node name
EventMessageChunk byName = chainActor.streamEvent(
    chain, input,
    event -> List.of("my_parser").contains(event.getName())
);

// Filter by node type
EventMessageChunk byType = chainActor.streamEvent(
    chain, input,
    event -> List.of("llm").contains(event.getType())
);

// Filter by tag
EventMessageChunk byTag = chainActor.streamEvent(
    chain, input,
    event -> Stream.of("my_chain").anyMatch(event.getTags()::contains)
);
```

---

## Pushing Streaming Output in Spring Boot

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

Frontend:
```javascript
const eventSource = new EventSource('/chat/stream?question=Hello');
eventSource.onmessage = (e) => {
    document.getElementById('output').textContent += e.data;
};
eventSource.addEventListener('done', () => eventSource.close());
```

---

## Streaming API Overview

| Method | Return Type | Use Case |
|--------|-------------|----------|
| `llm.stream(input)` | `AIMessageChunk` | Direct LLM streaming |
| `chainActor.stream(chain, input)` | `ChatGenerationChunk` | Chain streaming |
| `chainActor.streamEvent(chain, input)` | `EventMessageChunk` | Debugging / monitoring |
| `chainActor.streamEvent(chain, input, filter)` | `EventMessageChunk` | Filter specific node events |
| `chainActor.stop(chain)` | `void` | Cancel streaming generation |

---

> Full source code: `src/test/java/org/salt/jlangchain/demo/article/Article06Streaming.java`
