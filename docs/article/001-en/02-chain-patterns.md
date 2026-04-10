# Five Chain Orchestration Patterns for Java AI Apps

> **Audience**: Java developers who already know j-langchain basics  
> **Prerequisite**: [Article 1 – Build your first AI app in 5 minutes](01-hello-ai.md)

---

## What Is Chain Orchestration?

Calling an LLM once rarely solves real-world problems. Typical workflows include:

- Classify a query first, then route it to a specialized chain
- Generate content and run quality checks afterward
- Execute several subtasks in parallel and merge the answers
- Rewrite follow-up questions with chat history

j-langchain ships with six orchestration patterns that cover about 90% of AI application scenarios.

---

## Pattern Overview

```
Sequential: A → B → C → D
Conditional: A → [if cond1 → B | cond2 → C | default → D]
Compose: [A→B→C] → [output → D → E]
Parallel: [A→B] and [A→C] run at the same time → merge
Router: Classify with an LLM → send to the expert chain
Dynamic: Run branches in parallel → merge context → final answer
```

---

## Pattern 1: Sequential Chain

A straight pipeline where each node runs after the previous one.

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

**Use cases**: one-off Q&A, translation, summarization.

---

## Pattern 2: Conditional (Switch) Chain

Select a branch at runtime based on an input condition.

```java
@Test
public void switchChain() {
    ChatOllama ollamaModel = ChatOllama.builder().model("qwen2.5:0.5b").build();

    FlowInstance chain = chainActor.builder()
        .next(prompt)
        .next(
            Info.c("vendor == 'ollama'", ollamaModel),  // condition + branch
            Info.c(input -> "暂不支持该模型供应商")           // default branch
        )
        .next(new StrOutputParser())
        .build();

    chainActor.invoke(chain, Map.of("topic", "Java", "vendor", "ollama"));
}
```

**Key API**: `Info.c(condition, node)` supports SpEL expressions or lambdas.

**Use cases**: multi-model switching, A/B tests, permission-based routing.

---

## Pattern 3: Compose Chain

Use the output of one chain as the input to another to enable multi-step reasoning.

```java
@Test
public void composeChain() {
    FlowInstance jokeChain = chainActor.builder()
        .next(jokePrompt).next(llm).next(parser).build();

    FlowInstance analysisChain = chainActor.builder()
        .next(new InvokeChain(jokeChain))
        .next(input -> Map.of("joke", ((Generation) input).getText()))
        .next(analysisPrompt)
        .next(llm).next(parser)
        .build();

    chainActor.invoke(analysisChain, Map.of("topic", "程序员"));
}
```

**Key API**: `new InvokeChain(subChain)` embeds a subchain as a node.

**Use cases**: generate + review, multi-step reasoning, chain-of-thought prompts.

---

## Pattern 4: Parallel Chain

Run multiple subchains **at the same time** and merge their outputs to reduce latency.

```java
@Test
public void parallelChain() {
    FlowInstance jokeChain = chainActor.builder().next(jokePrompt).next(llm).build();
    FlowInstance poemChain = chainActor.builder().next(poemPrompt).next(llm).build();

    FlowInstance parallelChain = chainActor.builder()
        .concurrent(jokeChain, poemChain)
        .next(input -> {
            Map<String, Object> map = (Map<String, Object>) input;
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

**Key API**: `chainActor.builder().concurrent(chain1, chain2, …)` runs subchains in parallel; use the subchain `flowId` to read outputs.

**Use cases**: generate several pieces of content, query multiple sources, multi-model voting.

---

## Pattern 5: Router Chain

Classify the input with an LLM first, then route it to the specialized chain.

```java
@Test
public void routeChain() {
    FlowInstance classifyChain = chainActor.builder()
        .next(classifyPrompt).next(llm).next(new StrOutputParser()).build();

    FlowInstance fullChain = chainActor.builder()
        .next(new InvokeChain(classifyChain))
        .next(input -> Map.of(
            "category", input.toString(),
            "question", ((Map<?, ?>) ContextBus.get().getFlowParam()).get("question")
        ))
        .next(
            Info.c("category == '技术'", techChain),
            Info.c("category == '业务'", bizChain),
            Info.c(generalChain)
        )
        .build();

    chainActor.invoke(fullChain, Map.of("question", "如何优化 Java 内存？"));
}
```

**Tip**: `ContextBus.get().getFlowParam()` retrieves the original chain input from anywhere.

**Use cases**: smart customer service, multi-domain Q&A, expert routing.

---

## Pattern 6: Dynamic Context Chain

Rewrite follow-up questions with chat history, fetch context, then answer—this powers multi-turn RAG dialogs.

```java
@Test
public void dynamicChain() {
    FlowInstance contextualizeIfNeeded = chainActor.builder().next(
        Info.c("chatHistory != null", new InvokeChain(contextualizeChain)),
        Info.c(input -> Map.of("question", ((Map<String, String>) input).get("question")))
    ).build();

    FlowInstance fullChain = chainActor.builder()
        .all(
            Info.c(contextualizeIfNeeded),
            Info.c(input -> "印度尼西亚2024年人口约2.78亿").cAlias("retriever")
        )
        .next(input -> Map.of(
            "question", ContextBus.get().getResult(contextualizeIfNeeded.getFlowId()).toString(),
            "context", ContextBus.get().getResult("retriever")
        ))
        .next(qaPrompt).next(llm).next(new StrOutputParser())
        .build();

    chainActor.invoke(fullChain, Map.of(
        "question", "那印度呢",
        "chatHistory", List.of(
            Pair.of("human", "印度尼西亚有多少人口"),
            Pair.of("ai", "约2.78亿")
        )
    ));
}
```

**Key APIs**:
- `chainActor.builder().all(...)` runs all branches concurrently; `cAlias` assigns readable names.
- `ContextBus.get().getResult(id)` fetches any intermediate result by node ID or alias.

**Use cases**: multi-turn assistants with memory, context-aware QA.

---

## Pattern Comparison

| Pattern | Key API | Highlights | Typical Scenarios |
|---------|---------|------------|-------------------|
| Sequential | `.next()` | Linear pipeline | QA, translation |
| Conditional | `Info.c(condition, node)` | Runtime branch selection | Model switch, permissions |
| Compose | `new InvokeChain(chain)` | Chain nesting & multi-step reasoning | Generate + review |
| Parallel | `.concurrent(chain1, chain2)` | Run chains simultaneously | Concurrent generation, multi-source retrieval |
| Router | LLM classification + `Info.c(...)` | Intelligent routing | Customer support, expert systems |
| Dynamic | `.all()` + `ContextBus` | Parallel context gathering + merge | Multi-turn RAG |

---

> Full code: `src/test/java/org/salt/jlangchain/demo/article/Article02ChainPatterns.java`
