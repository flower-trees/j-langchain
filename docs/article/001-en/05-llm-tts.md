# Java AI + TTS: Let the LLM Speak

> **Audience**: Java developers who need speech synthesis (assistants, broadcast systems)  
> **Vendors**: Doubao (ByteDance) and Aliyun

---

## Why Combine LLM + TTS?

LLMs output text only, yet many scenarios require speech:

- Voice assistants that reply vocally
- News or alert broadcasting
- Accessibility interfaces for visually impaired users
- In-car experiences where screens are inconvenient

j-langchain unifies the workflow into `Prompt → LLM → TTS`, turning text into speech in one chain.

---

## Core Data Structures

### `TtsCard` (synchronous)

```java
TtsCard {
    String text;
    byte[] audio;
}
```

### `TtsCardChunk` (streaming)

Each chunk is either text or audio:

```java
TtsCardChunk {
    boolean audio; // false=text token, true=audio
    String text;
    byte[] audio;
    int index;     // audio packet ordering
}
```

This enables **subtitle/audio synchronization**: display tokens and play audio simultaneously.

---

## Mode 1: Synchronous

The LLM finishes the text before converting it to speech—suitable for short responses:

```java
@Test
public void ttsInvoke() {
    FlowInstance chain = chainActor.builder()
        .next(PromptTemplate.fromTemplate("用一段话介绍一下 ${topic}"))
        .next(ChatOllama.builder().model("qwen2.5:0.5b").build())
        .next(new StrOutputParser())
        .next(new DoubaoTts())
        .build();

    TtsCard result = chainActor.invoke(chain, Map.of("topic", "人工智能"));

    System.out.println("文字：" + result.getText());
}
```

---

## Mode 2: Streaming (Recommended)

The LLM and TTS run concurrently so text and audio stream out together:

```java
@Test
public void ttsDoubaoStream() throws TimeoutException {
    FlowInstance chain = chainActor.builder()
        .next(PromptTemplate.fromTemplate("用三句话介绍 ${topic}"))
        .next(ChatOllama.builder().model("qwen2.5:0.5b").build())
        .next(new StrOutputParser())
        .next(new DoubaoTts())
        .build();

    TtsCardChunk result = chainActor.stream(chain, Map.of("topic", "Java编程"));

    StringBuilder textSb = new StringBuilder();
    while (result.getIterator().hasNext()) {
        TtsCardChunk chunk = result.getIterator().next();
        if (!chunk.isAudio()) {
            textSb.append(chunk.getText());
            System.out.print(chunk.getText());
        } else {
            System.out.println("[音频包 #" + chunk.getIndex() + "]");
        }
    }
}
```

Timeline:

```
LLM tokens: [Java] [是一种] [面向对象] ...
TTS audio:      [pkt1]     [pkt2]    [pkt3]
Subtitles: Java 是一种 面向对象 ...
```

---

## Switch TTS Providers

Swap the final node only:

```java
.next(new DoubaoTts())   // Doubao
.next(new AliyunTts())   // Aliyun
```

---

## Voice Assistant Example

```java
@Test
public void voiceAssistant() throws TimeoutException {
    FlowInstance assistantChain = chainActor.builder()
        .next(PromptTemplate.fromTemplate(
            """
            你是一个专业、友好的语音助手。请用简洁的语言（不超过3句话）回答以下问题。
            问题：${question}
            回答：
            """
        ))
        .next(ChatOllama.builder().model("qwen2.5:0.5b").build())
        .next(new StrOutputParser())
        .next(new DoubaoTts())
        .build();

    TtsCardChunk result = chainActor.stream(
        assistantChain,
        Map.of("question", "今天适合运动吗？")
    );

    while (result.getIterator().hasNext()) {
        TtsCardChunk chunk = result.getIterator().next();
        if (!chunk.isAudio()) {
            System.out.print(chunk.getText());
        } else {
            // send chunk.getAudio() to the frontend
        }
    }
}
```

---

## Web API Integration

Use SSE to push text/audio to browsers:

```java
@GetMapping(value = "/chat/voice", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
public SseEmitter voiceChat(@RequestParam String question) {
    SseEmitter emitter = new SseEmitter();

    CompletableFuture.runAsync(() -> {
        TtsCardChunk result = chainActor.stream(voiceChain, Map.of("question", question));
        while (result.getIterator().hasNext()) {
            TtsCardChunk chunk = result.getIterator().next();
            if (!chunk.isAudio()) {
                emitter.send(SseEmitter.event().name("text").data(chunk.getText()));
            } else {
                String audioBase64 = Base64.getEncoder().encodeToString(chunk.getAudio());
                emitter.send(SseEmitter.event().name("audio").data(audioBase64));
            }
        }
        emitter.complete();
    });

    return emitter;
}
```

---

## Configuration

### Doubao

```yaml
tts:
  doubao:
    api-key: ${DOUBAO_TTS_KEY}
```

Customize parameters if needed:

```java
DoubaoTts tts = new DoubaoTts();
tts.setAppId("your_app_id");
tts.setVoiceType("S_nTxZIAta1");
tts.setCluster("volcano_icl");
```

### Aliyun

```yaml
tts:
  aliyun:
    api-key: ${ALIYUN_TTS_KEY}
    api-ak-id: ${ALIYUN_AK_ID}
    api-ak-secret: ${ALIYUN_AK_SECRET}
```

```java
AliyunTts tts = new AliyunTts();
tts.setAppkey("your_appkey");
tts.setVoice("zhiyan_emo");
```

---

> Full code: `src/test/java/org/salt/jlangchain/demo/article/Article05LlmTts.java`
