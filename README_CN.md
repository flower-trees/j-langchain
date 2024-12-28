# j-langchain

## 介绍
本项目是一个Java版的LangChain开发框架，旨在简化和加速各类大模型应用在Java平台的落地开发。它提供了一组实用的工具和类，使得开发人员能够更轻松地构建类似于LangChain的Java应用程序。

## 快速开始

### Maven
```xml
<dependency>
    <groupId>io.github.flower-trees</groupId>
    <artifactId>j-langchain</artifactId>
    <version>1.0.2-preview</version>
</dependency>
```

### Gradle
```groovy
implementation 'io.github.flower-trees:j-langchain:1.0.2-preview'
```

### 配置
```java
@Import(JLangchainConfig.class)
public class YourApplication {
    public static void main(String[] args) {
        SpringApplication.run(YourApplication.class, args);
    }
}
```
```shell
export ALIYUN_KEY=xxx-xxx-xxx-xxx
export CHATGPT_KEY=xxx-xxx-xxx-xxx
export DOUBAO_KEY=xxx-xxx-xxx-xxx
export MOONSHOT_KEY=xxx-xxx-xxx-xxx
```

### 使用

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
- 系统基于salt-function-flow流程编排框架开发，具体语法可 [参考](https://github.com/flower-trees/salt-function-flow)。
- 目前系统暂只提供预览版。

## 调用链

### 构建

#### 分支路由
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

#### 组合嵌套
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

#### 并行执行
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

#### 动态路由
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

#### 动态构建
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
