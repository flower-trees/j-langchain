# Reasoning Model Integration: Accessing DeepSeek-R1 and Qwen3 Thinking Content

> **Tags**: `Java` `reasoningContent` `DeepSeek-R1` `Qwen3` `reasoning model` `chain of thought` `j-langchain`  
> **Prerequisites**: [Build Your First AI App in Java in 5 Minutes](01-hello-ai.md)  
> **Audience**: Java developers integrating reasoning models (DeepSeek-R1, Qwen3) who need to access chain-of-thought output

---

## 1. Reasoning Models vs. Standard Models

Standard chat models produce a final answer directly. Reasoning models (DeepSeek-R1, Qwen3 with thinking enabled) reason before answering, producing two parts:

```
┌─────────────────────────────────┐
│  Thinking process (reasoningContent) │  ← reasoning chain, useful for debug/display
│  <think>                        │
│    ...step-by-step reasoning... │
│  </think>                       │
├─────────────────────────────────┤
│  Final answer (content)         │  ← what getText() returns
└─────────────────────────────────┘
```

j-langchain 1.0.16 maps reasoning content to a unified field regardless of how the vendor implements it (`<think>` tags or a `reasoning_content` protocol field) — the caller reads it the same way.

---

## 2. Enabling Reasoning

### 2.1 DeepSeek-R1

Use the `deepseek-reasoner` model — reasoning is on by default:

```java
ChatDeepseek llm = ChatDeepseek.builder()
        .model("deepseek-reasoner")
        .build();
```

### 2.2 Qwen3 with thinking

Pass `enable_thinking=true` through `modelKwargs`:

```java
ChatAliyun llm = ChatAliyun.builder()
        .model("qwen3-235b-a22b")
        .modelKwargs(Map.of("enable_thinking", true))
        .build();
```

> **Disabling reasoning** (faster, fewer tokens): pass `enable_thinking=false`.  
> Non-reasoning DeepSeek models (e.g. `deepseek-chat`) produce no reasoning content — `getReasoningContent()` returns null or empty.

---

## 3. Reading Reasoning Content

Call `llm.invoke()` directly — it returns an `AIMessage`. Access reasoning content via `getReasoningContent()`:

```java
ChatDeepseek llm = ChatDeepseek.builder().model("deepseek-reasoner").build();
AIMessage result = llm.invoke("Which is larger: 9.11 or 9.9?");

// Final answer
String answer = result.getContent();

// Reasoning chain
String reasoning = result.getReasoningContent();

System.out.println("Thinking:\n" + reasoning);
System.out.println("Answer:\n" + answer);
```

---

## 4. Using in a Chain

When a Prompt → LLM chain ends with an LLM node, `chainActor.invoke()` returns an `AIMessage` — the same type as a direct `llm.invoke()` call:

```java
BaseRunnable<StringPromptValue, ?> prompt = PromptTemplate.fromTemplate(
        "Answer: ${question}, showing your full reasoning."
);

ChatDeepseek llm = ChatDeepseek.builder()
        .model("deepseek-reasoner")
        .build();

FlowInstance chain = chainActor.builder()
        .next(prompt)
        .next(llm)
        .build();

AIMessage result = chainActor.invoke(
        chain, Map.of("question",
                "A class has 30 students, 60% are girls, 50% of the girls like math. How many girls like math?"));

System.out.println("Reasoning: " + result.getReasoningContent());
System.out.println("Answer: " + result.getContent());
```

**Note**: If you append a `StrOutputParser`, the return type becomes `ChatGeneration` but reasoning content is lost (the parser only preserves the final answer text). Omit the parser when you need reasoning content.

---

## 5. Typical Use Cases

### 5.1 Debugging complex reasoning errors

```java
AIMessage result = llm.invoke("Calculate sqrt(2) + sqrt(3) to 4 decimal places");
if (result.getReasoningContent() != null) {
    log.debug("Reasoning: {}", result.getReasoningContent());
}
log.info("Answer: {}", result.getContent());
```

### 5.2 Showing "thinking..." to users

Each chunk carries two independent fields: `reasoningContent` (reasoning tokens, arrive first) and `content` (answer tokens, arrive after reasoning finishes).

```java
AIMessageChunk stream = llm.stream("Explain the principle of quantum entanglement");
while (stream.getIterator().hasNext()) {
    AIMessageChunk chunk = stream.getIterator().next();
    if (chunk.getReasoningContent() != null && !chunk.getReasoningContent().isEmpty()) {
        ui.appendThinking(chunk.getReasoningContent()); // show thinking
    }
    if (chunk.getContent() != null && !chunk.getContent().isEmpty()) {
        ui.appendAnswer(chunk.getContent());             // show final answer
    }
}
// Full accumulated values after stream ends
String fullReasoning = stream.getReasoningContent();
String fullAnswer    = stream.getContent();
```

### 5.3 Token cost monitoring

Reasoning tokens are tracked separately in `AiTokenUsage.getReasoningTokens()`, enabling billing differentiation from answer tokens:

```java
AiTokenUsage usage = (AiTokenUsage) result.getResponseMetadata()
        .get(AiTokenUsage.METADATA_KEY);
System.out.println("reasoning tokens: " + usage.getReasoningTokens()
        + ", answer tokens: " + usage.getCompletionTokens());
```

---

## 6. DeepSeek vs Qwen3 Comparison

| Dimension | DeepSeek-R1 | Qwen3 (enable_thinking) |
|-----------|-------------|--------------------------|
| Model name | `deepseek-reasoner` | `qwen3-235b-a22b` etc. |
| Enable | Default on | `modelKwargs("enable_thinking", true)` |
| Disable | Use `deepseek-chat` | `modelKwargs("enable_thinking", false)` |
| Protocol | `reasoning_content` field | `<think>` tag (parsed & mapped) |
| Read in j-langchain | `getMessage().getReasoningContent()` | Same |

---

## 7. Prerequisites

| Test method | Required env var |
|-------------|-----------------|
| `testDeepSeekReasoningContent` | `DEEPSEEK_KEY` |
| `testQwenThinkingContent` | `ALIYUN_KEY` |
| `testReasoningInChain` | `DEEPSEEK_KEY` |
| `testReasoningDisabled` | `ALIYUN_KEY` |

---

## 8. Summary

1. **Unified interface**: DeepSeek-R1 and Qwen3 both use `getMessage().getReasoningContent()` — no vendor-specific code
2. **Backward-compatible**: `getReasoningContent()` returns null for non-reasoning models; existing code is unaffected
3. **Chain-compatible**: Reasoning content is accessible in standard Prompt → LLM chains — just omit `StrOutputParser`
4. **Cost tracking**: Reasoning tokens are counted separately in `AiTokenUsage.reasoningTokens`, distinct from `completionTokens`

---

> Related resources
> - Full code: [Article29ReasoningContent.java](../../../src/test/java/org/salt/jlangchain/demo/article/Article29ReasoningContent.java)
> - j-langchain GitHub: https://github.com/flower-trees/j-langchain
> - Runtime requirement: `DEEPSEEK_KEY` (DeepSeek-R1) or `ALIYUN_KEY` (Qwen3)
