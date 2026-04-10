# Switching Between 11 Chinese LLM Vendors with j-langchain

> **Tags**: Java, LLM, j-langchain, Qwen, DeepSeek, Hunyuan, Qianfan, GLM, Doubao, Kimi  
> **Prerequisite**: [Article 01](01-hello-ai.md)

---

## 1. Problem Statement

Once you’ve built a chain in j-langchain, swapping the underlying vendor should not require rewriting prompts or parsers. Each vendor implements `BaseChatModel`, so you only replace the `Chat*` class. Everything else—prompt templates, output parsers, handlers—stays the same.

---

## 2. Shared Chain Template

```java
private void runSimpleDomesticChain(String banner, BaseChatModel llm) {
    BaseRunnable<StringPromptValue, ?> prompt = PromptTemplate.fromTemplate(
        "请用一句话（不超过40字）中文回答：${topic}"
    );

    FlowInstance chain = chainActor.builder()
        .next(prompt)
        .next(llm)
        .next(new StrOutputParser())
        .build();

    ChatGeneration result = chainActor.invoke(chain, Map.of("topic", "简单自我介绍你的模型身份"));
    System.out.println("=== " + banner + " ===");
    System.out.println(result.getText());
}
```

Pass a different `Chat*` implementation and the chain works unchanged.

---

## 3. Vendor Examples

Each vendor requires only a builder call and an API key env var.

- **Aliyun Qwen**: `ChatAliyun.builder().model("qwen-plus")` → `ALIYUN_KEY`
- **Doubao (Volcengine)**: `ChatDoubao` → `DOUBAO_KEY`
- **Moonshot (Kimi)**: `ChatMoonshot` → `MOONSHOT_KEY`
- **Coze**: `ChatCoze` plus `botId`; skip test when `COZE_KEY` missing via `Assume`
- **DeepSeek**: `ChatDeepseek` → `DEEPSEEK_KEY`
- **Tencent Hunyuan**: `ChatHunyuan` → `HUNYUAN_KEY`
- **Baidu Qianfan (ERNIE)**: `ChatQianfan` → `QIANFAN_KEY`
- **Zhipu GLM**: `ChatZhipu` → `ZHIPU_KEY`
- **MiniMax**: `ChatMinimax` → `MINIMAX_KEY`
- **Yi (Lingyi Wanwu)**: `ChatLingyi` → `LINGYI_KEY`
- **Step (StepFun)**: `ChatStepfun` → `STEPFUN_KEY`

---

## 4. Quick Reference

| Vendor | Class | Env vars | Sample model |
|--------|-------|----------|---------------|
| Aliyun Qwen | `ChatAliyun` | `ALIYUN_KEY` | `qwen-plus` |
| Doubao | `ChatDoubao` | `DOUBAO_KEY` | `doubao-1-5-lite-32k-250115` |
| Moonshot | `ChatMoonshot` | `MOONSHOT_KEY` | `moonshot-v1-8k` |
| Coze | `ChatCoze` | `COZE_KEY`, `COZE_BOT_ID` | — |
| DeepSeek | `ChatDeepseek` | `DEEPSEEK_KEY` | `deepseek-chat` |
| Tencent Hunyuan | `ChatHunyuan` | `HUNYUAN_KEY` | `hunyuan-turbo` |
| Baidu Qianfan | `ChatQianfan` | `QIANFAN_KEY` | `ernie-4.5-8k` |
| Zhipu GLM | `ChatZhipu` | `ZHIPU_KEY` | `glm-4-flash` |
| MiniMax | `ChatMinimax` | `MINIMAX_KEY` | `MiniMax-Text-01` |
| Yi | `ChatLingyi` | `LINGYI_KEY` | `yi-lightning` |
| Step | `ChatStepfun` | `STEPFUN_KEY` | `step-2-16k` |

---

## 5. Running Tests

```bash
# Single vendor
dmvn test -Dtest=Article17DomesticVendorsChain#chainAliyun

# Entire suite
mvn test -Dtest=Article17DomesticVendorsChain
```

Vendors without keys will fail authentication except Coze, which is skipped via `Assume`.

---

## 6. Managing Keys

Keep keys in `application.yml` rather than scattering env lookups in code:

```yaml
models:
  aliyun:
    chat-key: ${ALIYUN_KEY}
    model: qwen-plus
  deepseek:
    chat-key: ${DEEPSEEK_KEY}
    model: deepseek-chat
```

Switching models becomes a config change, not a code change.

---

## 7. Summary

j-langchain offers a unified interface across domestic vendors. Prompt/chain code remains identical; swapping vendors is a one-line change to the `Chat*` instance. This makes A/B testing, fallback, and cost-based routing straightforward.

---

> Sample: `Article17DomesticVendorsChain.java` (requires the relevant API keys per vendor).
