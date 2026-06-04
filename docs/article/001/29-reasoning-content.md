# 推理模型集成：读取 DeepSeek-R1 与 Qwen3 的思考内容

> **标签**：`Java` `reasoningContent` `DeepSeek-R1` `Qwen3` `推理模型` `思维链` `j-langchain`  
> **前置阅读**：[5分钟用 Java 构建你的第一个 AI 应用](01-hello-ai.md)  
> **适合人群**：需要在 Java 应用中集成推理模型（DeepSeek-R1、Qwen3）并访问其思考过程的开发者

---

## 一、推理模型与普通模型的区别

普通 Chat 模型直接输出最终答案；推理模型（如 DeepSeek-R1、Qwen3 开启 thinking 模式）在输出答案前会先进行内部推理，输出包含两部分：

```
┌─────────────────────────────────┐
│  思考过程（reasoningContent）    │  ← 模型的推理链，可用于调试或展示
│  <think>                        │
│    ...一步一步的推理过程...       │
│  </think>                       │
├─────────────────────────────────┤
│  最终答案（content）             │  ← getText() 返回这部分
└─────────────────────────────────┘
```

j-langchain 1.0.16 将推理内容映射到统一字段，无论底层厂商如何实现（`<think>` 标签 / `reasoning_content` 字段），调用方读取方式一致。

---

## 二、如何启用推理

### 2.1 DeepSeek-R1

使用 `deepseek-reasoner` 模型，默认开启推理：

```java
ChatDeepseek llm = ChatDeepseek.builder()
        .model("deepseek-reasoner")
        .build();
```

### 2.2 Qwen3 开启 thinking

通过 `modelKwargs` 传入 `enable_thinking=true`：

```java
ChatAliyun llm = ChatAliyun.builder()
        .model("qwen3-235b-a22b")
        .modelKwargs(Map.of("enable_thinking", true))
        .build();
```

> **关闭推理**（更快、更省 token）：传入 `enable_thinking=false`。
> DeepSeek 非推理模型（如 `deepseek-chat`）不输出推理内容，
> 此时 `getReasoningContent()` 返回 null 或空字符串。

---

## 三、读取推理内容

直接调用 `llm.invoke()` 返回 `AIMessage`，通过 `getReasoningContent()` 获取推理内容：

```java
ChatDeepseek llm = ChatDeepseek.builder().model("deepseek-reasoner").build();
AIMessage result = llm.invoke("9.11 和 9.9 哪个更大？");

// 最终答案
String answer = result.getContent();

// 推理过程
String reasoning = result.getReasoningContent();

System.out.println("推理内容：\n" + reasoning);
System.out.println("最终答案：\n" + answer);
```

---

## 四、在 Chain 中使用

Prompt → LLM 链末节点为 LLM 时，`chainActor.invoke()` 返回 `AIMessage`，读取方式与直接调用完全一致：

```java
BaseRunnable<StringPromptValue, ?> prompt = PromptTemplate.fromTemplate(
        "请回答：${question}，需要展示完整的推理过程。"
);

ChatDeepseek llm = ChatDeepseek.builder()
        .model("deepseek-reasoner")
        .build();

FlowInstance chain = chainActor.builder()
        .next(prompt)
        .next(llm)
        .build();

AIMessage result = chainActor.invoke(
        chain, Map.of("question", "一个班级有 30 名学生，60% 是女生，女生中有 50% 喜欢数学，共有多少名女生喜欢数学？"));

System.out.println("推理：" + result.getReasoningContent());
System.out.println("答案：" + result.getContent());
```

**注意**：如果链末尾接了 `StrOutputParser`，返回类型变为 `ChatGeneration`，但推理内容会丢失（Parser 只保留最终答案文本）。需要推理内容时，链末不要加 Parser。

---

## 五、推理内容的典型使用场景

### 5.1 调试复杂推理错误

```java
AIMessage result = llm.invoke("计算 sqrt(2) + sqrt(3) 的近似值，精确到小数点后 4 位");
if (result.getReasoningContent() != null) {
    log.debug("推理过程：{}", result.getReasoningContent());
}
log.info("答案：{}", result.getContent());
```

### 5.2 展示给用户（"思考中..."效果）

每个 chunk 携带两个独立字段：`reasoningContent`（推理 token，先出现）和 `content`（答案 token，后出现）。

```java
AIMessageChunk stream = llm.stream("解释量子纠缠的原理");
while (stream.getIterator().hasNext()) {
    AIMessageChunk chunk = stream.getIterator().next();
    if (chunk.getReasoningContent() != null && !chunk.getReasoningContent().isEmpty()) {
        ui.appendThinking(chunk.getReasoningContent()); // 展示推理过程
    }
    if (chunk.getContent() != null && !chunk.getContent().isEmpty()) {
        ui.appendAnswer(chunk.getContent());             // 展示最终答案
    }
}
// 流结束后可读取完整累积值
String fullReasoning = stream.getReasoningContent();
String fullAnswer    = stream.getContent();
```

### 5.3 Token 成本监控

推理模型的 thinking token 通过 `AiTokenUsage.getReasoningTokens()` 单独统计，便于与 answer token 区分计费：

```java
AiTokenUsage usage = (AiTokenUsage) result.getResponseMetadata()
        .get(AiTokenUsage.METADATA_KEY);
if (usage != null) {
    System.out.println("推理 token: " + usage.getReasoningTokens()
            + "，答案 token: " + usage.getCompletionTokens());
}
```

---

## 六、DeepSeek vs Qwen3 对比

| 维度 | DeepSeek-R1 | Qwen3（enable_thinking） |
|------|-------------|--------------------------|
| 模型名 | `deepseek-reasoner` | `qwen3-235b-a22b` 等 |
| 启用方式 | 默认启用 | `modelKwargs("enable_thinking", true)` |
| 关闭方式 | 使用 `deepseek-chat` | `modelKwargs("enable_thinking", false)` |
| 推理内容字段 | `reasoning_content`（协议字段） | `<think>` 标签（解析后映射） |
| j-langchain 读取 | `getMessage().getReasoningContent()` | 相同 |

---

## 七、运行前置条件

| 测试方法 | 所需环境变量 |
|---------|------------|
| `testDeepSeekReasoningContent` | `DEEPSEEK_KEY` |
| `testQwenThinkingContent` | `ALIYUN_KEY` |
| `testReasoningInChain` | `DEEPSEEK_KEY` |
| `testReasoningDisabled` | `ALIYUN_KEY` |

---

## 八、总结

1. **统一接口**：无论 DeepSeek-R1 还是 Qwen3，均通过 `getMessage().getReasoningContent()` 读取推理内容
2. **向后兼容**：非推理模型 `getReasoningContent()` 返回 null，不影响现有代码
3. **Chain 兼容**：推理内容在标准 Prompt → LLM 链中同样可访问，只需不加 StrOutputParser
4. **Token 计费**：推理 token 单独统计到 `AiTokenUsage.reasoningTokens`，不混入 completionTokens

---

> 相关资源
> - 完整代码：[Article29ReasoningContent.java](../../../src/test/java/org/salt/jlangchain/demo/article/Article29ReasoningContent.java)
> - j-langchain GitHub：https://github.com/flower-trees/j-langchain
> - 运行环境：`DEEPSEEK_KEY`（DeepSeek-R1）或 `ALIYUN_KEY`（Qwen3）
