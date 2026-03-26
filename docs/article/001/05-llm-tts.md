# Java AI + TTS：让大模型开口说话

> **适合人群**：需要语音合成功能的 Java 开发者（语音助手、播报系统）  
> **支持厂商**：豆包（字节跳动）、阿里云

---

## 为什么需要 LLM + TTS？

大模型只能输出文字，但很多场景需要语音：

- **智能语音助手**：用户语音提问，AI 语音回答
- **内容播报**：新闻、公告、实时数据的语音播报
- **无障碍应用**：为视障用户提供语音界面
- **车载系统**：驾驶时不方便看屏幕

j-langchain 将 LLM 和 TTS 统一在一条链中，`Prompt → LLM → TTS` 三步完成文字到语音的全链路。

---

## 核心数据结构

### TtsCard（同步结果）

```java
TtsCard {
    String text;    // 完整文字内容
    byte[] audio;   // PCM/MP3 音频数据
}
```

### TtsCardChunk（流式结果）

流式输出时，每个 `chunk` 要么是文字，要么是音频：

```java
TtsCardChunk {
    boolean audio;  // false=文字 token，true=音频数据
    String text;    // 文字内容（audio=false 时有值）
    byte[] audio;   // 音频数据（audio=true 时有值）
    int index;      // 音频包序号（用于顺序播放）
}
```

这种设计使"**字幕和语音同步**"成为可能：文字 token 用于实时显示字幕，音频数据用于实时播放。

---

## 方式1：同步调用

LLM 先生成完整文字，再一次性转为语音。适合短文本、对延迟不敏感的场景：

```java
@Test
public void ttsInvoke() {
    FlowInstance chain = chainActor.builder()
        .next(PromptTemplate.fromTemplate("用一段话介绍一下 ${topic}"))
        .next(ChatOllama.builder().model("qwen2.5:0.5b").build())
        .next(new StrOutputParser())  // 提取文本
        .next(new DoubaoTts())        // 文本 → 语音
        .build();

    TtsCard result = chainActor.invoke(chain, Map.of("topic", "人工智能"));

    System.out.println("文字：" + result.getText());
    // result.getAudio() → byte[]，即为音频数据，可写文件或发送给前端
}
```

---

## 方式2：流式输出（推荐）

LLM 边生成文字，TTS 边合成语音，文字和音频**同时流出**。延迟最低，用户体验最好：

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
            // 文字 token：实时显示字幕
            textSb.append(chunk.getText());
            System.out.print(chunk.getText());
        } else {
            // 音频数据：实时播放
            // playAudio(chunk.getAudio());  // 发给前端或本地播放
            System.out.println("[音频包 #" + chunk.getIndex() + "]");
        }
    }
}
```

流式输出示意图：

```
时间轴 ──────────────────────────────────────────────►

LLM:   [Java] [是一种] [面向对象] [的编程] [语言...]
TTS:         [音频1]     [音频2]    [音频3]
字幕:  Java 是一种 面向对象 的编程 语言...
播放:        ♪♪♪♪      ♪♪♪♪     ♪♪♪♪
```

---

## 切换 TTS 供应商

只需替换链中的 TTS 节点，其余代码不变：

```java
// 豆包 TTS（字节跳动）
.next(new DoubaoTts())

// 阿里云 TTS
.next(new AliyunTts())
```

---

## 完整语音助手示例

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
            System.out.print(chunk.getText()); // 字幕
        } else {
            // chunk.getAudio() 发给前端播放
        }
    }
}
```

---

## 实际集成到 Web API

在 Spring Boot 中，可以通过 SSE（Server-Sent Events）将文字和音频一起推送给前端：

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
                // 音频 Base64 编码后推送
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

## 配置说明

### 豆包 TTS 配置

豆包 TTS 鉴权 token 通过 `application.yml` 配置，其余参数（appId、voiceType 等）在构造 `DoubaoTts` 时传入：

```yaml
tts:
  doubao:
    api-key: ${DOUBAO_TTS_KEY}   # 豆包 TTS Access Token
```

`DoubaoTts` 节点的参数默认值可直接使用，如需自定义：

```java
DoubaoTts tts = new DoubaoTts();
tts.setAppId("your_app_id");
tts.setVoiceType("S_nTxZIAta1");   // 音色 ID
tts.setCluster("volcano_icl");
```

### 阿里云 TTS 配置

阿里云 TTS 支持两种鉴权方式，均在 `application.yml` 中配置：

```yaml
tts:
  aliyun:
    # 方式一：直接使用 Access Token（推荐测试用）
    api-key: ${ALIYUN_TTS_KEY}

    # 方式二：用 AK/SK 动态换取 Token（推荐生产用，api-key 为空时自动启用）
    api-ak-id: ${ALIYUN_AK_ID}
    api-ak-secret: ${ALIYUN_AK_SECRET}
```

`AliyunTts` 节点的 appkey、voice 等参数在构造时传入：

```java
AliyunTts tts = new AliyunTts();
tts.setAppkey("your_appkey");
tts.setVoice("zhiyan_emo");   // 发音人
```

---

> 完整代码见：`src/test/java/org/salt/jlangchain/demo/article/Article05LlmTts.java`
