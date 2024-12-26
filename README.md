# j-langchain

## Introduction
This project is a Java-based LangChain development framework aimed at simplifying and accelerating the development and implementation of various large model applications on the Java platform. It provides a set of useful tools and classes, making it easier for developers to build Java applications similar to LangChain.

## quick start

### Maven
```xml
<dependency>
    <groupId>io.github.flower-trees</groupId>
    <artifactId>j-langchain</artifactId>
    <version>1.0.1-preview</version>
</dependency>
```

### Gradle
```groovy
implementation 'io.github.flower-trees:j-langchain:1.0.1-preview'
```

### Use

```java
@Component
public class ChainBuildDemo {

    @Autowired
    ChainActor chainActor;

    public void SimpleDemo() {

        BaseRunnable<StringPromptValue, ?> prompt = PromptTemplate.fromTemplate("tell me a joke about ${topic}");
        ChatOpenAI chatOpenAI = ChatOpenAI.builder().model("gpt-4").build();

        FlowInstance chain = chainActor.builder().next(prompt).next(oll).next(new StrOutputParser()).build();

        ChatGeneration result = chainActor.invoke(chain, Map.of("topic", "bears"));
        System.out.println(result);
    }
}
```
💡 **Notes:**
- The system is developed based on the salt-function-flow workflow orchestration framework. For specific syntax, please [refer to](https://github.com/flower-trees/salt-function-flow) the documentation.
- Currently, the system only provides a preview version.

## Call Chain

### Construction

#### Switch Route
```java
public void SwitchDemo() {

    BaseRunnable<StringPromptValue, ?> prompt = PromptTemplate.fromTemplate("tell me a joke about ${topic}");
    ChatOllama chatOllama = ChatOllama.builder().model("llama3:8b").build();
    ChatOpenAI chatOpenAI = ChatOpenAI.builder().model("gpt-4").build();

    FlowInstance chain = chainActor.builder()
            .next(prompt)
            .next(
                    Info.c("vendor == 'ollama'", chatOllama),
                    Info.c("vendor == 'chatgpt'", chatOpenAI),
                    Info.c(input -> "sorry, I don't know how to do that")
            )
            .next(new StrOutputParser()).build();

    Generation result = chainActor.invoke(chain, Map.of("topic", "bears", "vendor", "ollama"));
    System.out.println(result);
}
```

#### Combination nesting
```java
public void ComposeDemo() {

        ChatOllama llm = ChatOllama.builder().model("llama3:8b").build();
        StrOutputParser parser = new StrOutputParser();

        BaseRunnable<StringPromptValue, ?> prompt = PromptTemplate.fromTemplate("tell me a joke about ${topic}");
        FlowInstance chain = chainActor.builder().next(prompt).next(llm).next(parser).build();

        BaseRunnable<StringPromptValue, ?> analysisPrompt = PromptTemplate.fromTemplate("is this a funny joke? ${joke}");

        FlowInstance analysisChain = chainActor.builder()
                .next(chain)
                .next(input -> Map.of("joke", ((Generation)input).getText()))
                .next(analysisPrompt)
                .next(llm)
                .next(parser).build();

        ChatGeneration result = chainActor.invoke(analysisChain, Map.of("topic", "bears"));
        System.out.println(result);
    }
```

#### Parallel
```java
public void ParallelDemo() {
        ChatOllama llm = ChatOllama.builder().model("llama3:8b").build();

        BaseRunnable<StringPromptValue, ?> joke = PromptTemplate.fromTemplate("tell me a joke about ${topic}");
        BaseRunnable<StringPromptValue, ?> poem = PromptTemplate.fromTemplate("write a 2-line poem about ${topic}");

        FlowInstance jokeChain = chainActor.builder().next(joke).next(llm).build();
        FlowInstance poemChain = chainActor.builder().next(poem).next(llm).build();

        FlowInstance chain = chainActor.builder().concurrent(
                (IResult<Map<String, String>>) (iContextBus, isTimeout) -> {
                    AIMessage jokeResult = iContextBus.getResult(jokeChain.getFlowId());
                    AIMessage poemResult = iContextBus.getResult(poemChain.getFlowId());
                    return Map.of("joke", jokeResult.getContent(), "poem", poemResult.getContent());
                }, jokeChain, poemChain).build();

        Map<String, String> result = chainActor.invoke(chain, Map.of("topic", "bears"));
        System.out.println(JsonUtil.toJson(result));
    }
```

#### Dynamic Routing
```java
public void RouteDemo() {
        ChatOllama llm = ChatOllama.builder().model("llama3:8b").build();

        BaseRunnable<StringPromptValue, Object> prompt = PromptTemplate.fromTemplate(
                """
                Given the user question below, classify it as either being about `LangChain`, `Anthropic`, or `Other`.
        
                Do not respond with more than one word.
        
                <question>
                ${question}
                </question>
        
                Classification:
                """
        );

        FlowInstance chain = chainActor.builder().next(prompt).next(llm).next(new StrOutputParser()).build();

        FlowInstance langchainChain = chainActor.builder().next(PromptTemplate.fromTemplate(
                """
                You are an expert in langchain. \
                Always answer questions starting with "As Harrison Chase told me". \
                Respond to the following question:
                
                Question: ${question}
                Answer:
                """
        )).next(ChatOllama.builder().model("llama3:8b").build()).build();

        FlowInstance anthropicChain = chainActor.builder().next(PromptTemplate.fromTemplate(
                """
                You are an expert in anthropic. \
                Always answer questions starting with "As Dario Amodei told me". \
                Respond to the following question:
            
                Question: ${question}
                Answer:
                """
        )).next(ChatOllama.builder().model("llama3:8b").build()).build();

        FlowInstance generalChain = chainActor.builder().next(PromptTemplate.fromTemplate(
                """
                Respond to the following question:
            
                Question: ${question}
                Answer:
                """
        )).next(ChatOllama.builder().model("llama3:8b").build()).build();

        FlowInstance fullChain = chainActor.builder()
                .next(chain)
                .next(input -> Map.of("topic", input, "question", ((Map<?, ?>)ContextBus.get().getFlowParam()).get("question")))
                .next(
                        Info.c("topic == 'anthropic'", anthropicChain),
                        Info.c("topic == 'langchain'", langchainChain),
                        Info.c(generalChain)
                ).build();

        AIMessage result = chainActor.invoke(fullChain, Map.of("question", "how do I use Anthropic?"));
        System.out.println(result.getContent());
    }
```

#### Dynamic Construction
```java
public void DynamicDemo() {
        ChatOllama llm = ChatOllama.builder().model("llama3:8b").build();

        String contextualizeInstructions = """
                Convert the latest user question into a standalone question given the chat history. Don't answer the question, return the question and nothing else (no descriptive text).""";

        BaseRunnable<ChatPromptValue, Object> contextualizePrompt = ChatPromptTemplate.fromMessages(
                List.of(
                        Pair.of("system", contextualizeInstructions),
                        Pair.of("placeholder", "${chatHistory}"),
                        Pair.of("human", "${question}")
                )
        );

        FlowInstance contextualizeQuestion = chainActor.builder()
                .next(contextualizePrompt)
                .next(llm)
                .next(new StrOutputParser())
                .build();

        FlowInstance contextualizeIfNeeded = chainActor.builder().next(
                Info.c("chatHistory != null", contextualizeQuestion),
                Info.c(input -> Map.of("question", ((Map<String, String>)input).get("question")))
        ).build();

        String qaInstructions =
                """
                Answer the user question given the following context:\n\n${context}.
                """;
        BaseRunnable<ChatPromptValue, Object>  qaPrompt = ChatPromptTemplate.fromMessages(
                List.of(
                        Pair.of("system", qaInstructions),
                        Pair.of("human", "${question}")
                )
        );

        FlowInstance fullChain = chainActor.builder()
                .all(
                        (iContextBus, isTimeout) -> Map.of(
                                "question", iContextBus.getResult(contextualizeIfNeeded.getFlowId()).toString(),
                                "context", iContextBus.getResult("fakeRetriever")),
                        Info.c(contextualizeIfNeeded),
                        Info.c(input -> "egypt's population in 2024 is about 111 million").cAlias("fakeRetriever")
                )
                .next(qaPrompt)
                .next(input -> {System.out.println(JsonUtil.toJson(input)); return input;})
                .next(llm)
                .next(new StrOutputParser())
                .build();

        ChatGeneration result = chainActor.invoke(fullChain,
                Map.of(
                        "question", "what about egypt",
                        "chatHistory",
                                List.of(
                                        Pair.of("human", "what's the population of indonesia"),
                                        Pair.of("ai", "about 276 million")
                                )
                )
        );
        System.out.println(result);
    }
```