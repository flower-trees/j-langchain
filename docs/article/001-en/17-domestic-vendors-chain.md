# Integrating 11 Chinese LLM Vendors with j-langchain: Switch with One Line of Code

> **Tags**: `Java` `LLM` `j-langchain` `Qwen` `DeepSeek` `Hunyuan` `Qianfan` `GLM` `Doubao` `Kimi` `Chain`  
> **Prerequisite**: [Build Your First AI App in Java in 5 Minutes](01-hello-ai.md)  
> **Audience**: Java developers who need to integrate Chinese LLM vendors and want a unified pipeline codebase

---

## 1. The Problem: Does Every Vendor Require Its Own Adapter?

After building a chain with j-langchain, if you want to switch to a different LLM vendor, there are usually two concerns: what if the API format is different? Does the existing Prompt and parsing logic need to change too?

The answer is no. j-langchain implements a unified `BaseChatModel` interface for all models. Each vendor has a corresponding `Chat*` class (`ChatAliyun`, `ChatDeepseek`, `ChatHunyuan`…), and the rest of the pipeline — `PromptTemplate`, `StrOutputParser`, all `TranslateHandler` nodes — requires zero changes.

Switching vendors requires changing **one line of code**. The rest is handled by the framework.

---

## 2. The Unified Pipeline Template

All vendors share the same pipeline structure:

```java
private void runSimpleDomesticChain(String banner, BaseChatModel llm) {

    // Prompt template: universal across all vendors, no per-vendor customization needed
    BaseRunnable<StringPromptValue, ?> prompt = PromptTemplate.fromTemplate(
        "请用一句话（不超过40字）中文回答：${topic}"
    );

    // Fixed chain structure: PromptTemplate → LLM → StrOutputParser
    FlowInstance chain = chainActor.builder()
        .next(prompt)
        .next(llm)   // ← swap in a different Chat* instance here; everything else is unchanged
        .next(new StrOutputParser())
        .build();

    ChatGeneration result = chainActor.invoke(chain, Map.of("topic", "简单自我介绍你的模型身份"));
    System.out.println("=== " + banner + " ===");
    System.out.println(result.getText());
}
```

This method has exactly one variation point: the `llm` parameter passed in. All vendor examples below reuse this method.

---

## 3. Integrating 11 Vendors

### Aliyun Qwen

```java
@Test
public void chainAliyun() {
    runSimpleDomesticChain(
        "阿里云通义",
        ChatAliyun.builder().model("qwen-plus").build()
    );
}
```

Environment variable: `ALIYUN_KEY`

---

### Doubao (Volcengine Ark)

```java
@Test
public void chainDoubao() {
    runSimpleDomesticChain(
        "豆包 / 火山方舟",
        ChatDoubao.builder().model("doubao-1-5-lite-32k-250115").build()
    );
}
```

Environment variable: `DOUBAO_KEY`

---

### Moonshot (Kimi)

```java
@Test
public void chainMoonshot() {
    runSimpleDomesticChain(
        "Moonshot Kimi",
        ChatMoonshot.builder().model("moonshot-v1-8k").build()
    );
}
```

Environment variable: `MOONSHOT_KEY`

---

### Coze

Coze's integration differs slightly — it requires an additional `botId`. You can create a Bot in the Coze console, then set the Bot ID as the environment variable `COZE_BOT_ID`. Without it, the example uses a placeholder ID and calls will fail until replaced with a real value.

The code uses JUnit's `Assume` mechanism: if `COZE_KEY` is not configured, the test is automatically skipped without affecting other test cases:

```java
@Test
public void chainCoze() {
    // Auto-skip if COZE_KEY is not configured, no error thrown
    Assume.assumeTrue("需要 COZE_KEY", StringUtils.isNotBlank(System.getenv("COZE_KEY")));
    String botId = StringUtils.defaultIfBlank(System.getenv("COZE_BOT_ID"), "751971414224112XXXX");
    runSimpleDomesticChain(
        "扣子 Coze",
        ChatCoze.builder().botId(botId).build()
    );
}
```

Environment variables: `COZE_KEY` + `COZE_BOT_ID`

---

### DeepSeek

```java
@Test
public void chainDeepseek() {
    runSimpleDomesticChain(
        "DeepSeek",
        ChatDeepseek.builder().model("deepseek-chat").build()
    );
}
```

Environment variable: `DEEPSEEK_KEY`

---

### Tencent Hunyuan

```java
@Test
public void chainHunyuan() {
    runSimpleDomesticChain(
        "腾讯混元",
        ChatHunyuan.builder().model("hunyuan-turbo").build()
    );
}
```

Environment variable: `HUNYUAN_KEY`

---

### Baidu Qianfan (ERNIE)

```java
@Test
public void chainQianfan() {
    runSimpleDomesticChain(
        "百度千帆文心",
        ChatQianfan.builder().model("ernie-4.5-8k").build()
    );
}
```

Environment variable: `QIANFAN_KEY`

---

### Zhipu GLM

```java
@Test
public void chainZhipu() {
    runSimpleDomesticChain(
        "智谱 GLM",
        ChatZhipu.builder().model("glm-4-flash").build()
    );
}
```

Environment variable: `ZHIPU_KEY`

---

### MiniMax

```java
@Test
public void chainMinimax() {
    runSimpleDomesticChain(
        "MiniMax",
        ChatMinimax.builder().model("MiniMax-Text-01").build()
    );
}
```

Environment variable: `MINIMAX_KEY`

---

### Lingyi Wanwu (Yi)

```java
@Test
public void chainLingyi() {
    runSimpleDomesticChain(
        "零一万物 Yi",
        ChatLingyi.builder().model("yi-lightning").build()
    );
}
```

Environment variable: `LINGYI_KEY`

---

### Stepfun (Step)

```java
@Test
public void chainStepfun() {
    runSimpleDomesticChain(
        "阶跃星辰 Step",
        ChatStepfun.builder().model("step-2-16k").build()
    );
}
```

Environment variable: `STEPFUN_KEY`

---

## 4. Vendor Quick Reference

| Vendor | Chat class | Environment variable | Example model |
|---|---|---|---|
| Aliyun Qwen | `ChatAliyun` | `ALIYUN_KEY` | `qwen-plus` |
| Doubao (Volcengine) | `ChatDoubao` | `DOUBAO_KEY` | `doubao-1-5-lite-32k-250115` |
| Moonshot (Kimi) | `ChatMoonshot` | `MOONSHOT_KEY` | `moonshot-v1-8k` |
| Coze | `ChatCoze` | `COZE_KEY` + `COZE_BOT_ID` | — |
| DeepSeek | `ChatDeepseek` | `DEEPSEEK_KEY` | `deepseek-chat` |
| Tencent Hunyuan | `ChatHunyuan` | `HUNYUAN_KEY` | `hunyuan-turbo` |
| Baidu Qianfan | `ChatQianfan` | `QIANFAN_KEY` | `ernie-4.5-8k` |
| Zhipu GLM | `ChatZhipu` | `ZHIPU_KEY` | `glm-4-flash` |
| MiniMax | `ChatMinimax` | `MINIMAX_KEY` | `MiniMax-Text-01` |
| Lingyi Yi | `ChatLingyi` | `LINGYI_KEY` | `yi-lightning` |
| Stepfun Step | `ChatStepfun` | `STEPFUN_KEY` | `step-2-16k` |

The model name can be changed to any version available in the vendor's console; the `Chat*` class and environment variable stay the same.

---

## 5. How to Run

Run a single vendor's test (export the corresponding API key first):

```bash
# Run Aliyun only
mvn test -Dtest=Article17DomesticVendorsChain#chainAliyun

# Run DeepSeek only
mvn test -Dtest=Article17DomesticVendorsChain#chainDeepseek
```

Run all:

```bash
mvn test -Dtest=Article17DomesticVendorsChain
```

Vendors whose keys are not configured will throw an authentication exception without affecting other test cases. Coze is the only special case that uses `Assume` to actively skip, because it additionally depends on `botId` and there's no generic way to detect readiness.

---

## 6. Managing Multiple Vendor Keys in Production

Environment variables are fine for test environments. For production, it's recommended to configure them centrally in `application.yml`, injected via Spring's `@Value` or a configuration class, to avoid keys scattered everywhere:

```yaml
models:
  aliyun:
    chat-key: ${ALIYUN_KEY}
    model: qwen-plus
  deepseek:
    chat-key: ${DEEPSEEK_KEY}
    model: deepseek-chat
  hunyuan:
    chat-key: ${HUNYUAN_KEY}
    model: hunyuan-turbo
```

This has two additional benefits: you can configure different model versions for different environments (dev/staging/prod); when you need to switch the primary model, only the config file changes — not the code.

---

## 7. Summary

j-langchain's approach to Chinese LLM vendors is **unified interface, varied implementation**: all `Chat*` classes implement `BaseChatModel`; the differences are encapsulated inside each class, completely transparent to the pipeline.

This means: a pipeline once written — whether a simple sequential chain, a tool-using ReAct Agent, or a multi-Agent pipeline — only needs the `Chat*` instance swapped to change models; all other code stays unchanged. In scenarios where you need to compare different model outputs, or dynamically switch based on cost/performance, this design is very practical.

---

> 📎 Resources
> - Full example: `Article17DomesticVendorsChain.java`
> - j-langchain GitHub: https://github.com/flower-trees/j-langchain
> - j-langchain Gitee mirror: https://gitee.com/flower-trees-z/j-langchain
> - API key registration pages for each vendor: see project README
