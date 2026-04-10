# Java AI + TTS: Let the LLM Speak

> **Audience**: Java developers who need speech synthesis (voice assistants, broadcast systems)  
> **Supported vendors**: Doubao (ByteDance), Alibaba Cloud

---

## Why LLM + TTS?

Large models only output text, but many scenarios require speech:

- **Intelligent voice assistants**: users ask by voice, AI answers by voice
- **Content broadcasting**: voice broadcast of news, announcements, and real-time data
- **Accessibility**: voice interface for visually impaired users
- **In-vehicle systems**: not convenient to look at a screen while driving

j-langchain unifies LLM and TTS in a single chain. Three steps — `Prompt → LLM → TTS` — complete the full text-to-speech pipeline.

---

## Core Data Structures

### TtsCard (synchronous result)

```java
TtsCard {
    String text;    // Complete text content
    byte[] audio;   // PCM/MP3 audio data
}
```

### TtsCardChunk (streaming result)

In streaming mode, each `chunk` is either text or audio:

```java
TtsCardChunk {
    boolean audio;  // false = text token, true = audio data
    String text;    // Text content (has a value when audio=false)
    byte[] audio;   // Audio data (has a value when audio=true)
    int index;      // Audio packet sequence number (for in-order playback)
}
```

This design enables **synchronized subtitles and audio**: text tokens are used to display real-time subtitles, while audio data is used for real-time playback.

---

## Method 1: Synchronous Call

The LLM generates the full text first, then converts it to speech all at once. Suitable for short text or latency-insensitive scenarios:

```java
@Test
public void ttsInvoke() {
    FlowInstance chain = chainActor.builder()
        .next(PromptTemplate.fromTemplate("Write a brief introduction of ${topic}"))
        .next(ChatOllama.builder().model("qwen2.5:0.5b").build())
        .next(new StrOutputParser())  // Extract text
        .next(new DoubaoTts())        // Text → Speech
        .build();

    TtsCard result = chainActor.invoke(chain, Map.of("topic", "artificial intelligence"));

    System.out.println("Text: " + result.getText());
    // result.getAudio() → byte[], which is the audio data; write to file or send to frontend
}
```

---

## Method 2: Streaming Output (Recommended)

The LLM generates text while TTS synthesizes speech simultaneously — text and audio **stream out together**. Lowest latency, best user experience:

```java
@Test
public void ttsDoubaoStream() throws TimeoutException {
    FlowInstance chain = chainActor.builder()
        .next(PromptTemplate.fromTemplate("Introduce ${topic} in three sentences"))
        .next(ChatOllama.builder().model("qwen2.5:0.5b").build())
        .next(new StrOutputParser())
        .next(new DoubaoTts())
        .build();

    TtsCardChunk result = chainActor.stream(chain, Map.of("topic", "Java programming"));

    StringBuilder textSb = new StringBuilder();
    while (result.getIterator().hasNext()) {
        TtsCardChunk chunk = result.getIterator().next();

        if (!chunk.isAudio()) {
            // Text token: display real-time subtitles
            textSb.append(chunk.getText());
            System.out.print(chunk.getText());
        } else {
            // Audio data: play in real time
            // playAudio(chunk.getAudio());  // Send to frontend or play locally
            System.out.println("[Audio packet #" + chunk.getIndex() + "]");
        }
    }
}
```

Streaming output timeline:

```
Timeline ──────────────────────────────────────────────►

LLM:      [Java] [is a] [object-oriented] [programming] [language...]
TTS:             [audio1]     [audio2]       [audio3]
Subtitles: Java  is a   object-oriented  programming  language...
Playback:        ♪♪♪♪         ♪♪♪♪          ♪♪♪♪
```

---

## Switching TTS Vendors

Just replace the TTS node in the chain — everything else stays the same:

```java
// Doubao TTS (ByteDance)
.next(new DoubaoTts())

// Alibaba Cloud TTS
.next(new AliyunTts())
```

---

## Complete Voice Assistant Example

```java
@Test
public void voiceAssistant() throws TimeoutException {
    FlowInstance assistantChain = chainActor.builder()
        .next(PromptTemplate.fromTemplate(
            """
            You are a professional, friendly voice assistant. Answer the following question
            concisely in no more than 3 sentences.
            Question: ${question}
            Answer:
            """
        ))
        .next(ChatOllama.builder().model("qwen2.5:0.5b").build())
        .next(new StrOutputParser())
        .next(new DoubaoTts())
        .build();

    TtsCardChunk result = chainActor.stream(
        assistantChain,
        Map.of("question", "Is today a good day for exercise?")
    );

    while (result.getIterator().hasNext()) {
        TtsCardChunk chunk = result.getIterator().next();
        if (!chunk.isAudio()) {
            System.out.print(chunk.getText()); // Subtitles
        } else {
            // chunk.getAudio() — send to frontend for playback
        }
    }
}
```

---

## Integrating with a Web API

In Spring Boot, you can push both text and audio to the frontend via SSE (Server-Sent Events):

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
                // Base64-encode the audio before pushing
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

### Doubao TTS Configuration

The Doubao TTS authentication token is configured in `application.yml`; other parameters (appId, voiceType, etc.) are passed when constructing `DoubaoTts`:

```yaml
tts:
  doubao:
    api-key: ${DOUBAO_TTS_KEY}   # Doubao TTS Access Token
```

The default parameter values of the `DoubaoTts` node can be used directly. For customization:

```java
DoubaoTts tts = new DoubaoTts();
tts.setAppId("your_app_id");
tts.setVoiceType("S_nTxZIAta1");   // Voice ID
tts.setCluster("volcano_icl");
```

### Alibaba Cloud TTS Configuration

Alibaba Cloud TTS supports two authentication methods, both configured in `application.yml`:

```yaml
tts:
  aliyun:
    # Method 1: Use an Access Token directly (recommended for testing)
    api-key: ${ALIYUN_TTS_KEY}

    # Method 2: Exchange AK/SK for a Token dynamically (recommended for production; auto-enabled when api-key is empty)
    api-ak-id: ${ALIYUN_AK_ID}
    api-ak-secret: ${ALIYUN_AK_SECRET}
```

Parameters such as appkey and voice for `AliyunTts` are passed at construction:

```java
AliyunTts tts = new AliyunTts();
tts.setAppkey("your_appkey");
tts.setVoice("zhiyan_emo");   // Voice name
```

---

> Full source code: `src/test/java/org/salt/jlangchain/demo/article/Article05LlmTts.java`
