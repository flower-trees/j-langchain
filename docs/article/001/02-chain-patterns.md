# Java AI 应用的 5 种链式编排模式

> **适合人群**：了解 j-langchain 基础的 Java 开发者  
> **前置阅读**：[文章1：5分钟构建第一个AI应用](01-hello-ai.md)

---

## 什么是链式编排？

在 AI 应用开发中，单纯调用一次 LLM 往往不够用。实际场景需要：

- 先分类问题，再路由到专业处理链
- 先生成内容，再对内容做质量检查
- 多个子任务并行执行，最后汇总结果
- 结合对话历史，动态改写问题

j-langchain 提供了 6 种开箱即用的链式编排模式，覆盖 90% 的 AI 应用场景。

---

## 模式总览

```
顺序链：A → B → C → D
条件链：A → [条件1→B | 条件2→C | 默认→D]
组合链：[A→B→C] → [上一链的输出→D→E]
并行链：[A→B] 和 [A→C] 并行 → 合并结果
路由链：先用 LLM 分类 → 路由到对应专业链
动态链：并行执行多个分支 → 汇总上下文 → 最终答复
```

---

## 模式1：顺序链（Simple Chain）

最基础的线性流水线，节点按顺序依次执行。

```java
@Test
public void simpleChain() {
    BaseRunnable<StringPromptValue, ?> prompt = PromptTemplate.fromTemplate("讲一个关于 ${topic} 的笑话");
    ChatOllama llm = ChatOllama.builder().model("qwen2.5:0.5b").build();

    FlowInstance chain = chainActor.builder()
        .next(prompt)
        .next(llm)
        .next(new StrOutputParser())
        .build();

    ChatGeneration result = chainActor.invoke(chain, Map.of("topic", "程序员"));
    System.out.println(result.getText());
}
```

**适用场景**：问答、翻译、摘要等单轮任务。

---

## 模式2：条件链（Switch Chain）

根据输入参数中的条件，动态选择不同的处理分支。

```java
@Test
public void switchChain() {
    ChatOllama ollamaModel = ChatOllama.builder().model("qwen2.5:0.5b").build();

    FlowInstance chain = chainActor.builder()
        .next(prompt)
        .next(
            Info.c("vendor == 'ollama'", ollamaModel),  // 条件表达式 + 分支节点
            Info.c(input -> "暂不支持该模型供应商")       // 默认分支
        )
        .next(new StrOutputParser())
        .build();

    // 传入 vendor 参数控制走哪个分支
    chainActor.invoke(chain, Map.of("topic", "Java", "vendor", "ollama"));
}
```

**关键 API**：`Info.c(condition, node)` — 条件表达式支持 SpEL，也支持 Lambda。

**适用场景**：多模型切换、A/B 测试、权限分发。

---

## 模式3：组合链（Compose Chain）

将一个链的输出作为另一个链的输入，实现多步推理。

```java
@Test
public void composeChain() {
    // 第一个链：生成笑话
    FlowInstance jokeChain = chainActor.builder()
        .next(jokePrompt).next(llm).next(parser).build();

    // 第二个链：分析笑话
    FlowInstance analysisChain = chainActor.builder()
        .next(new InvokeChain(jokeChain))                          // 内嵌第一个链
        .next(input -> Map.of("joke", ((Generation) input).getText())) // 转换格式
        .next(analysisPrompt)
        .next(llm).next(parser)
        .build();

    chainActor.invoke(analysisChain, Map.of("topic", "程序员"));
}
```

**关键 API**：`new InvokeChain(subChain)` — 将子链作为一个节点嵌入。

**适用场景**：先生成再审核、多步骤推理、链式思考（CoT）。

---

## 模式4：并行链（Parallel Chain）

多个子链**同时**执行，执行完毕后合并结果。相比顺序执行，大幅降低延迟。

```java
@Test
public void parallelChain() {
    FlowInstance jokeChain = chainActor.builder().next(jokePrompt).next(llm).build();
    FlowInstance poemChain = chainActor.builder().next(poemPrompt).next(llm).build();

    FlowInstance parallelChain = chainActor.builder()
        .concurrent(jokeChain, poemChain)  // 并行执行两个链
        .next(input -> {
            Map<String, Object> map = (Map<String, Object>) input;
            // 用 flowId 获取对应子链的结果，兼容 AIMessage 和其他类型
            Object jokeResult = map.get(jokeChain.getFlowId());
            Object poemResult = map.get(poemChain.getFlowId());
            String joke = jokeResult instanceof AIMessage ? ((AIMessage) jokeResult).getContent() : String.valueOf(jokeResult);
            String poem = poemResult instanceof AIMessage ? ((AIMessage) poemResult).getContent() : String.valueOf(poemResult);
            return Map.of("joke", joke, "poem", poem);
        })
        .build();

    Map<String, String> result = chainActor.invoke(parallelChain, Map.of("topic", "猫"));
    System.out.println("笑话：" + result.get("joke"));
    System.out.println("诗歌：" + result.get("poem"));
}
```

**关键 API**：`chainActor.builder().concurrent(chain1, chain2, ...)` — 并行执行，用子链 flowId 取结果。

**适用场景**：同时生成多种内容、并发搜索多个数据源、多模型投票。

---

## 模式5：路由链（Route Chain）

先用 LLM 对输入内容自动分类，再根据分类结果路由到对应的专业链。

```java
@Test
public void routeChain() {
    // 分类链：判断问题类型
    FlowInstance classifyChain = chainActor.builder()
        .next(classifyPrompt).next(llm).next(new StrOutputParser()).build();

    FlowInstance fullChain = chainActor.builder()
        .next(new InvokeChain(classifyChain))
        .next(input -> Map.of(
            "category", input.toString(),
            "question", ((Map<?, ?>) ContextBus.get().getFlowParam()).get("question")
        ))
        .next(
            Info.c("category == '技术'", techChain),   // 路由到技术专家
            Info.c("category == '业务'", bizChain),    // 路由到业务专家
            Info.c(generalChain)                       // 默认通用回答
        )
        .build();

    chainActor.invoke(fullChain, Map.of("question", "如何优化 Java 内存？"));
}
```

**关键点**：`ContextBus.get().getFlowParam()` 可以在任意节点获取链的原始输入。

**适用场景**：智能客服、多领域问答、专家路由系统。

---

## 模式6：动态上下文链（Dynamic Chain）

结合对话历史，将"接上文的问题"改写为独立问题，再进行检索和回答。这是多轮对话 RAG 的核心模式。

```java
@Test
public void dynamicChain() {
    // 上下文改写：有历史时改写问题，无历史直接透传
    FlowInstance contextualizeIfNeeded = chainActor.builder().next(
        Info.c("chatHistory != null", new InvokeChain(contextualizeChain)),
        Info.c(input -> Map.of("question", ((Map<String, String>) input).get("question")))
    ).build();

    FlowInstance fullChain = chainActor.builder()
        .all(
            Info.c(contextualizeIfNeeded),                                              // 并行：改写问题
            Info.c(input -> "印度尼西亚2024年人口约2.78亿").cAlias("retriever")  // 并行：检索上下文
        )
        .next(input -> Map.of(
            "question", ContextBus.get().getResult(contextualizeIfNeeded.getFlowId()).toString(),
            "context",  ContextBus.get().getResult("retriever")
        ))
        .next(qaPrompt).next(llm).next(new StrOutputParser())
        .build();

    // 第二轮对话：问题依赖第一轮上下文
    chainActor.invoke(fullChain, Map.of(
        "question",    "那印度呢",
        "chatHistory", List.of(
            Pair.of("human", "印度尼西亚有多少人口"),
            Pair.of("ai",    "约2.78亿")
        )
    ));
}
```

**关键 API**：
- `chainActor.builder().all(...)` — 所有分支并行执行，用 `cAlias` 给分支命名
- `ContextBus.get().getResult(id)` — 按节点 ID 或别名获取中间结果

**适用场景**：多轮对话、带记忆的 AI 助手、上下文感知问答。

---

## 模式对比

| 模式 | 关键 API | 核心特点 | 典型场景 |
|------|----------|----------|----------|
| 顺序链 | `.next()` | 线性流水线 | 问答、翻译 |
| 条件链 | `Info.c(condition, node)` | 运行时分支选择 | 模型切换、权限控制 |
| 组合链 | `new InvokeChain(chain)` | 链嵌套，多步推理 | 生成+审核 |
| 并行链 | `.concurrent(chain1, chain2)` | 多链同时执行 | 并发生成、多源检索 |
| 路由链 | LLM分类 + `Info.c(条件, chain)` | 智能路由 | 智能客服、专家系统 |
| 动态链 | `.all()` + `ContextBus` | 并行+上下文汇聚 | 多轮对话RAG |

---

> 完整代码见：`src/test/java/org/salt/jlangchain/demo/article/Article02ChainPatterns.java`
