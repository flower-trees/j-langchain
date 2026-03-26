# Java AI 应用的流式输出：从原理到实战

> **适合人群**：需要实现打字机效果、实时对话的 Java 后端工程师  
> **核心 API**：`stream()`、`streamEvent()`、`stop()`

---

## 为什么流式输出如此重要？

**同步模式**：等待 LLM 生成完所有内容后再展示 → 用户等待 5~30 秒，体验极差  
**流式模式**：LLM 每生成一个 token 就立即推送 → 用户看到"打字机"效果，感知延迟降至 <1 秒

ChatGPT、Claude、通义千问的对话界面都使用流式输出。这是 AI 聊天产品的**标配技术**。

---

## 流式输出的原理

```
LLM 生成：[你] [好] [，] [我] [是] [AI] [助] [手]
               ↓每生成一个 token 就推送
前端接收：你 → 你好 → 你好， → 你好，我 → ...
```

HTTP 层面使用 **SSE（Server-Sent Events）** 或 **WebSocket** 传输。j-langchain 在 Java 层面用**阻塞迭代器**封装，开发者无需关心底层协议。

---

## 方式1：LLM 直接流式

最基础的用法，直接调用 `llm.stream()`：

```java
@Test
public void basicStream() throws TimeoutException {
    ChatOllama llm = ChatOllama.builder().model("qwen2.5:0.5b").build();

    // stream() 立即返回，不等待 LLM 完成
    AIMessageChunk chunk = llm.stream("天空是什么颜色？");

    while (chunk.getIterator().hasNext()) {
        String token = chunk.getIterator().next().getContent();
        System.out.print(token);  // 逐 token 打印
    }
}
```

`AIMessageChunk.getIterator()` 是一个**阻塞迭代器**：
- `hasNext()` 会阻塞，直到下一个 token 到达或生成结束
- `next()` 返回下一个 token 的 `AIMessageChunk`
- 生成结束后，`hasNext()` 返回 `false`

---

## 方式2：链式流式输出

整条链（Prompt → LLM → Parser）都是流式的：

```java
@Test
public void chainStream() throws TimeoutException {
    FlowInstance chain = chainActor.builder()
        .next(PromptTemplate.fromTemplate("讲一个关于 ${topic} 的笑话"))
        .next(ChatOllama.builder().model("qwen2.5:0.5b").build())
        .next(new StrOutputParser())
        .build();

    // chainActor.stream() 返回 ChatGenerationChunk
    ChatGenerationChunk chunk = chainActor.stream(chain, Map.of("topic", "程序员"));

    while (chunk.getIterator().hasNext()) {
        System.out.print(chunk.getIterator().next().getText());
    }
}
```

**invoke vs stream 的区别**：

| | `invoke()` | `stream()` |
|--|-----------|-----------|
| 返回时机 | 全部生成完后返回 | 立即返回，迭代器懒加载 |
| 返回类型 | `ChatGeneration` | `ChatGenerationChunk` |
| 适用场景 | 后台任务、批处理 | 实时对话、UI 交互 |

---

## 方式3：中途取消流式生成

用户点"停止生成"时，可以立即中断：

```java
@Test
public void streamWithStop() throws TimeoutException {
    FlowInstance chain = chainActor.builder()
        .next(prompt).next(llm).next(new StrOutputParser()).build();

    ChatGenerationChunk chunk = chainActor.stream(chain, Map.of("topic", "太空探索"));

    int tokenCount = 0;
    while (chunk.getIterator().hasNext()) {
        System.out.print(chunk.getIterator().next().getText());
        tokenCount++;

        if (tokenCount >= 5) {
            chainActor.stop(chain);  // 立即停止，释放资源
            System.out.println("\n[已停止]");
            break;
        }
    }
}
```

`chainActor.stop(chain)` 会：
1. 向 LLM 发送取消信号
2. 清理流式连接
3. 后续 `hasNext()` 返回 `false`

---

## 方式4：流式 JSON 输出

LLM 边生成 JSON，`JsonOutputParser` 边解析，实时返回每一步的 JSON 状态：

```java
@Test
public void jsonStream() throws TimeoutException {
    FlowInstance chain = chainActor.builder()
        .next(ChatOllama.builder().model("qwen2.5:0.5b").build())
        .next(new JsonOutputParser())  // 流式 JSON 解析
        .build();

    ChatGenerationChunk chunk = chainActor.stream(
        chain, "以 JSON 格式输出3个国家及其人口"
    );

    while (chunk.getIterator().hasNext()) {
        System.out.println(chunk.getIterator().next());
        // 每次输出的是当前已解析的部分 JSON
    }
}
```

---

## 方式5：事件流——调试利器

`streamEvent()` 返回链路中每个节点的**执行事件**，包括：
- `on_chain_start`：节点开始执行
- `on_llm_stream`：LLM 流式 token
- `on_chain_end`：节点执行完毕

```java
@Test
public void eventStream() throws TimeoutException {
    FlowInstance chain = chainActor.builder()
        .next(prompt).next(llm).next(new StrOutputParser()).build();

    EventMessageChunk events = chainActor.streamEvent(chain, Map.of("topic", "狗"));

    while (events.getIterator().hasNext()) {
        EventMessageChunk event = events.getIterator().next();
        System.out.println(event.toJson());
        // {"type": "llm", "name": "ChatOllama", "event": "on_llm_stream", "data": {...}}
    }
}
```

---

## 方式6：事件流过滤

事件太多时，可以按名称、类型、标签过滤，只关注你关心的事件：

```java
// 给节点打上名称和标签
FlowInstance chain = chainActor.builder()
    .next(llm.withConfig(Map.of("run_name", "my_llm")))
    .next(parser.withConfig(Map.of("run_name", "my_parser", "tags", List.of("my_chain"))))
    .build();

// 按节点名称过滤
EventMessageChunk byName = chainActor.streamEvent(
    chain, input,
    event -> List.of("my_parser").contains(event.getName())
);

// 按节点类型过滤
EventMessageChunk byType = chainActor.streamEvent(
    chain, input,
    event -> List.of("llm").contains(event.getType())
);

// 按标签过滤
EventMessageChunk byTag = chainActor.streamEvent(
    chain, input,
    event -> Stream.of("my_chain").anyMatch(event.getTags()::contains)
);
```

---

## 在 Spring Boot 中推送流式输出

### SSE 方式（推荐）

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

前端接收：
```javascript
const eventSource = new EventSource('/chat/stream?question=你好');
eventSource.onmessage = (e) => {
    document.getElementById('output').textContent += e.data;
};
eventSource.addEventListener('done', () => eventSource.close());
```

---

## 流式 API 总览

| 方法 | 返回类型 | 适用场景 |
|------|----------|----------|
| `llm.stream(input)` | `AIMessageChunk` | LLM 直接流式 |
| `chainActor.stream(chain, input)` | `ChatGenerationChunk` | 链式流式 |
| `chainActor.streamEvent(chain, input)` | `EventMessageChunk` | 调试/监控 |
| `chainActor.streamEvent(chain, input, filter)` | `EventMessageChunk` | 过滤特定节点事件 |
| `chainActor.stop(chain)` | `void` | 取消流式生成 |

---

> 完整代码见：`src/test/java/org/salt/jlangchain/demo/article/Article06Streaming.java`
