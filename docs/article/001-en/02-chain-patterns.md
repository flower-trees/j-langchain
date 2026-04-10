# 5 Chain Orchestration Patterns for Java AI Apps

> **Audience**: Java developers familiar with j-langchain basics  
> **Prerequisite**: [Article 1: Build Your First AI App in 5 Minutes](01-hello-ai.md)

---

## What Is Chain Orchestration?

A single LLM call is rarely enough in real-world AI applications. Practical scenarios require:

- Classifying a question first, then routing it to a specialized chain
- Generating content, then running a quality check on it
- Executing multiple sub-tasks in parallel and aggregating results
- Rewriting a question dynamically based on conversation history

j-langchain provides 6 ready-to-use chain orchestration patterns that cover 90% of AI application scenarios.

---

## Pattern Overview

```
Sequential:  A → B → C → D
Conditional: A → [condition1→B | condition2→C | default→D]
Compose:     [A→B→C] → [output of previous chain → D→E]
Parallel:    [A→B] and [A→C] run in parallel → merge results
Route:       LLM classifies first → routes to the matching specialized chain
Dynamic:     Multiple branches run in parallel → aggregate context → final reply
```

---

## Pattern 1: Sequential Chain (Simple Chain)

The most basic linear pipeline — nodes execute one after another in order.

```java
@Test
public void simpleChain() {
    BaseRunnable<StringPromptValue, ?> prompt = PromptTemplate.fromTemplate("Tell a joke about ${topic}");
    ChatOllama llm = ChatOllama.builder().model("qwen2.5:0.5b").build();

    FlowInstance chain = chainActor.builder()
        .next(prompt)
        .next(llm)
        .next(new StrOutputParser())
        .build();

    ChatGeneration result = chainActor.invoke(chain, Map.of("topic", "programmers"));
    System.out.println(result.getText());
}
```

**Use cases**: Q&A, translation, summarization, and other single-turn tasks.

---

## Pattern 2: Conditional Chain (Switch Chain)

Dynamically selects a different processing branch based on a condition in the input parameters.

```java
@Test
public void switchChain() {
    ChatOllama ollamaModel = ChatOllama.builder().model("qwen2.5:0.5b").build();

    FlowInstance chain = chainActor.builder()
        .next(prompt)
        .next(
            Info.c("vendor == 'ollama'", ollamaModel),  // condition expression + branch node
            Info.c(input -> "This model vendor is not supported yet") // default branch
        )
        .next(new StrOutputParser())
        .build();

    // Pass the vendor parameter to control which branch is taken
    chainActor.invoke(chain, Map.of("topic", "Java", "vendor", "ollama"));
}
```

**Key API**: `Info.c(condition, node)` — condition expressions support SpEL as well as Lambdas.

**Use cases**: Multi-model switching, A/B testing, permission-based routing.

---

## Pattern 3: Compose Chain

Uses the output of one chain as the input of another, enabling multi-step reasoning.

```java
@Test
public void composeChain() {
    // First chain: generate a joke
    FlowInstance jokeChain = chainActor.builder()
        .next(jokePrompt).next(llm).next(parser).build();

    // Second chain: analyze the joke
    FlowInstance analysisChain = chainActor.builder()
        .next(new InvokeChain(jokeChain))                          // embed the first chain
        .next(input -> Map.of("joke", ((Generation) input).getText())) // transform output
        .next(analysisPrompt)
        .next(llm).next(parser)
        .build();

    chainActor.invoke(analysisChain, Map.of("topic", "programmers"));
}
```

**Key API**: `new InvokeChain(subChain)` — embeds a sub-chain as a single node.

**Use cases**: Generate then review, multi-step reasoning, chain-of-thought (CoT).

---

## Pattern 4: Parallel Chain

Multiple sub-chains execute **simultaneously** and their results are merged when all complete. This dramatically reduces latency compared to sequential execution.

```java
@Test
public void parallelChain() {
    FlowInstance jokeChain = chainActor.builder().next(jokePrompt).next(llm).build();
    FlowInstance poemChain = chainActor.builder().next(poemPrompt).next(llm).build();

    FlowInstance parallelChain = chainActor.builder()
        .concurrent(jokeChain, poemChain)  // run both chains in parallel
        .next(input -> {
            Map<String, Object> map = (Map<String, Object>) input;
            // Use flowId to retrieve each sub-chain's result
            Object jokeResult = map.get(jokeChain.getFlowId());
            Object poemResult = map.get(poemChain.getFlowId());
            String joke = jokeResult instanceof AIMessage ? ((AIMessage) jokeResult).getContent() : String.valueOf(jokeResult);
            String poem = poemResult instanceof AIMessage ? ((AIMessage) poemResult).getContent() : String.valueOf(poemResult);
            return Map.of("joke", joke, "poem", poem);
        })
        .build();

    Map<String, String> result = chainActor.invoke(parallelChain, Map.of("topic", "cats"));
    System.out.println("Joke: " + result.get("joke"));
    System.out.println("Poem: " + result.get("poem"));
}
```

**Key API**: `chainActor.builder().concurrent(chain1, chain2, ...)` — runs chains in parallel; use each sub-chain's `flowId` to access its result.

**Use cases**: Generating multiple types of content simultaneously, searching multiple data sources concurrently, multi-model voting.

---

## Pattern 5: Route Chain

Uses an LLM to automatically classify the input, then routes it to the appropriate specialized chain based on the classification.

```java
@Test
public void routeChain() {
    // Classification chain: determines the question type
    FlowInstance classifyChain = chainActor.builder()
        .next(classifyPrompt).next(llm).next(new StrOutputParser()).build();

    FlowInstance fullChain = chainActor.builder()
        .next(new InvokeChain(classifyChain))
        .next(input -> Map.of(
            "category", input.toString(),
            "question", ((Map<?, ?>) ContextBus.get().getFlowParam()).get("question")
        ))
        .next(
            Info.c("category == 'technical'", techChain),  // route to the tech expert
            Info.c("category == 'business'", bizChain),   // route to the business expert
            Info.c(generalChain)                           // default: general answer
        )
        .build();

    chainActor.invoke(fullChain, Map.of("question", "How do I optimize Java memory usage?"));
}
```

**Key point**: `ContextBus.get().getFlowParam()` retrieves the chain's original input from any node.

**Use cases**: Intelligent customer service, multi-domain Q&A, expert routing systems.

---

## Pattern 6: Dynamic Context Chain

Combines conversation history to rewrite a follow-up question into a standalone question, then performs retrieval and answers. This is the core pattern for multi-turn conversational RAG.

```java
@Test
public void dynamicChain() {
    // Contextualize: rewrite if there is history, otherwise pass through directly
    FlowInstance contextualizeIfNeeded = chainActor.builder().next(
        Info.c("chatHistory != null", new InvokeChain(contextualizeChain)),
        Info.c(input -> Map.of("question", ((Map<String, String>) input).get("question")))
    ).build();

    FlowInstance fullChain = chainActor.builder()
        .all(
            Info.c(contextualizeIfNeeded),                                               // parallel: rewrite question
            Info.c(input -> "Indonesia's 2024 population is approximately 278 million").cAlias("retriever") // parallel: retrieve context
        )
        .next(input -> Map.of(
            "question", ContextBus.get().getResult(contextualizeIfNeeded.getFlowId()).toString(),
            "context",  ContextBus.get().getResult("retriever")
        ))
        .next(qaPrompt).next(llm).next(new StrOutputParser())
        .build();

    // Second turn: the question depends on the context from the first turn
    chainActor.invoke(fullChain, Map.of(
        "question",    "What about India?",
        "chatHistory", List.of(
            Pair.of("human", "What is Indonesia's population?"),
            Pair.of("ai",    "About 278 million")
        )
    ));
}
```

**Key APIs**:
- `chainActor.builder().all(...)` — all branches run in parallel; use `cAlias` to name branches
- `ContextBus.get().getResult(id)` — retrieve an intermediate result by node ID or alias

**Use cases**: Multi-turn conversation, AI assistants with memory, context-aware Q&A.

---

## Pattern Comparison

| Pattern | Key API | Core Characteristic | Typical Use Case |
|---------|---------|---------------------|-----------------|
| Sequential | `.next()` | Linear pipeline | Q&A, translation |
| Conditional | `Info.c(condition, node)` | Runtime branch selection | Model switching, access control |
| Compose | `new InvokeChain(chain)` | Nested chains, multi-step reasoning | Generate + review |
| Parallel | `.concurrent(chain1, chain2)` | Multiple chains execute simultaneously | Concurrent generation, multi-source retrieval |
| Route | LLM classification + `Info.c(condition, chain)` | Intelligent routing | Customer service, expert systems |
| Dynamic | `.all()` + `ContextBus` | Parallel + context aggregation | Multi-turn conversational RAG |

---

> Full code: [`src/test/java/org/salt/jlangchain/demo/article/Article02ChainPatterns.java`](/src/test/java/org/salt/jlangchain/demo/article/Article02ChainPatterns.java)
