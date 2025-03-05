# j-langchain

[中文](https://github.com/flower-trees/j-langchain/blob/master/README_CN.md)

## Introduction
This project is a Java-based LangChain development framework aimed at simplifying and accelerating the development and implementation of various large model applications on the Java platform. It provides a set of useful tools and classes, making it easier for developers to build Java applications similar to LangChain.

## quick start

### Maven
```xml
<dependency>
    <groupId>io.github.flower-trees</groupId>
    <artifactId>j-langchain</artifactId>
    <version>1.0.8-preview</version>
</dependency>
```

### Gradle
```groovy
implementation 'io.github.flower-trees:j-langchain:1.0.8-preview'
```

### Config

```java
@Import(JLangchainConfig.class)
public class YourApplication {
    public static void main(String[] args) {
        SpringApplication.run(YourApplication.class, args);
    }
}
```
```shell
export CHATGPT_KEY=xxx-xxx-xxx-xxx
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

## Call Chain Construction

### Switch Route

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

### Combination nesting

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

### Parallel

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

### Dynamic Routing

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

### Dynamic Construction

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

## Flow operation

### Using Flow

```java
@Component
public class ChainExtDemo {

    @Autowired
    ChainActor chainActor;

    public void StreamDemo() throws TimeoutException, InterruptedException {

        ChatOllama llm = ChatOllama.builder().model("llama3:8b").build();

        AIMessageChunk chunk = llm.stream("what color is the sky?");
        StringBuilder sb = new StringBuilder();
        while (chunk.getIterator().hasNext()) {
            sb.append(chunk.getIterator().next().getContent()).append("|");
            System.out.println(sb);
        }
    }
}
```

**Output**
```
The|
The| sky|
The| sky| is|
The| sky| is| blue|
The| sky| is| blue|.|
The| sky| is| blue|.||
```

### Call Chain Flow Execution

```java
public void ChainStreamDemo() throws TimeoutException, InterruptedException {

	ChatOllama llm = ChatOllama.builder().model("llama3:8b").build();
	
    BaseRunnable<StringPromptValue, ?> prompt = PromptTemplate.fromTemplate("tell me a joke about ${topic}");
    StrOutputParser parser = new StrOutputParser();

    FlowInstance chain = chainActor.builder().next(prompt).next(llm).next(parser).build();

    ChatGenerationChunk chunk = chainActor.stream(chain, Map.of("topic", "parrot"));
    StringBuilder sb = new StringBuilder();
    while (chunk.getIterator().hasNext()) {
        sb.append(chunk.getIterator().next()).append("|");
        System.out.println(sb);
    }
}
```

### Process input stream

**Output JSON**
```java
public void InputDemo() throws TimeoutException, InterruptedException {
    
    ChatOllama model = ChatOllama.builder().model("llama3:8b").build();
    
    FlowInstance chain = chainActor.builder().next(model).next(new JsonOutputParser()).build();
    
    ChatGenerationChunk chunk = chainActor.stream(chain, "output a list of countries and their populations in JSON format. limit 3 countries.");
    while (chunk.getIterator().hasNext()) {
        System.out.println(chunk.getIterator().next());
    }
}
```

**Output**
```
[]
[]
[{}]
[{}]
[{"country":""}]
[{"country":"China"}]
[{"country":"China","population":143}]
[{"country":"China","population":143932}]
[{"country":"China","population":143932377}]
[{"country":"China","population":1439323776}]
[{"country":"China","population":1439323776}]
[{"country":"China","population":1439323776}]
[{"country":"China","population":1439323776},{}]
......
```

### Generator function

```java
public void OutputFunctionDemo() throws TimeoutException, InterruptedException {

    ChatOllama llm = ChatOllama.builder().model("llama3:8b").build();

    FlowInstance chain = chainActor.builder()
            .next(llm)
            .next(new JsonOutputParser())
            .next(new FunctionOutputParser(this::extractCountryNamesStreaming))
            .build();

    ChatGenerationChunk chunk = chainActor.stream(chain, """
    output a list of the countries france, spain and japan and their populations in JSON format. "
    'Use a dict with an outer key of "countries" which contains a list of countries. '
    "Each country should have the key `name` and `population`""");

    StringBuilder sb = new StringBuilder();
    while (chunk.getIterator().hasNext()) {
        ChatGenerationChunk chunkIterator = chunk.getIterator().next();
        if (StringUtils.isNotEmpty(chunkIterator.getText())) {
            sb.append(chunkIterator).append("|");
            System.out.println(sb);
        }
    }
}

Set<Object> set = new HashSet<>();
private String extractCountryNamesStreaming(String chunk) {
    if (JsonUtil.isValidJson(chunk)) {
        Map chunkMap = JsonUtil.fromJson(chunk, Map.class);
        if (chunkMap != null && chunkMap.get("countries") != null) {
            Map countries = (Map) chunkMap.get("countries");
            for (Object name : countries.keySet()) {
                if (!set.contains(name)) {
                    set.add(name);
                    return (String) name;
                }
            }
        }
    }
    return "";
}
```

**Output**
```
France|
France|Spain|
France|Spain|Japan|
```
### Using Stream Events

J-LangChain provides an API for event streaming processing (` streamEvents `), which supports streaming monitoring of intermediate steps.

```java
public void EventDemo() throws TimeoutException {
    ChatOllama model = ChatOllama.builder().model("llama3:8b").build();

    List<EventMessageChunk> events = new ArrayList<>();
    EventMessageChunk chunk = model.streamEvent("hello");
    while (chunk.getIterator().hasNext()) {
        events.add(chunk.getIterator().next());
    }
    events.subList(events.size()-3, events.size()).forEach(event -> System.out.println(event.toJson()));
}
```

**Output**
```
{"event":"on_llm_stream","data":{"chunk":{"role":"ai","content":".","last":false}},"name":"ChatOllama","parentIds":[],"metadata":{"ls_model_name":"gpt-4","ls_provider":"chatgpt","ls_model_type":"llm"},"tags":[]}
{"event":"on_llm_stream","data":{"chunk":{"role":"ai","content":"","finishReason":"stop","last":true}},"name":"ChatOllama","parentIds":[],"metadata":{"ls_model_name":"gpt-4","ls_provider":"chatgpt","ls_model_type":"llm"},"tags":[]}
{"event":"on_llm_end","data":{"output":"Hello! How can I help you today? Let me know if you have any questions or need assistance with anything else."},"name":"ChatOllama","parentIds":[],"metadata":{},"tags":[]}
```

### Call Chain Flow Event

```java
public void EventChainDemo() throws TimeoutException {

    ChatOllama oll = ChatOllama.builder().model("llama3:8b").build();
    
    BaseRunnable<StringPromptValue, ?> prompt = PromptTemplate.fromTemplate("tell me a joke about ${topic}");
    
    FlowInstance chain = chainActor.builder().next(prompt).next(oll).next(new StrOutputParser()).build();

    EventMessageChunk chunk = chainActor.streamEvent(chain, Map.of("topic", "dog"));
    while (chunk.getIterator().hasNext()) {
        System.out.println(chunk.getIterator().next().toJson());
    }
}
```

**Output**
```
{"event":"on_chain_start","data":{"input":{"topic":"dog"}},"name":"ChainActor","runId":"9fbfb04d3101465daa9e762716a59ecd","parentIds":[],"metadata":{},"tags":[]}
{"event":"on_prompt_start","data":{"input":{"topic":"dog"}},"name":"PromptTemplate","runId":"6f0a038fe9a847768922ce2c559f06ec","parentIds":["9fbfb04d3101465daa9e762716a59ecd"],"metadata":{},"tags":[]}
{"event":"on_prompt_end","data":{"output":{"text":"tell me a joke about dog"}},"name":"PromptTemplate","runId":"6f0a038fe9a847768922ce2c559f06ec","parentIds":["9fbfb04d3101465daa9e762716a59ecd"],"metadata":{},"tags":[]}
{"event":"on_llm_start","data":{"input":{"text":"tell me a joke about dog"}},"name":"ChatOllama","runId":"0e21f6ad4fc84c16a50b3db3d3d54193","parentIds":["6f0a038fe9a847768922ce2c559f06ec"],"metadata":{"ls_model_type":"llm","ls_provider":"chatgpt","ls_model_name":"gpt-4"},"tags":[]}
{"event":"on_parser_start","data":{"input":{"role":"ai","last":false}},"name":"StrOutputParser","runId":"d1d0efcadd904b2090f4d202003d4c04","parentIds":["0e21f6ad4fc84c16a50b3db3d3d54193"],"metadata":{},"tags":[]}
{"event":"on_llm_stream","data":{"chunk":{"role":"ai","content":"Why","last":false}},"name":"ChatOllama","runId":"0e21f6ad4fc84c16a50b3db3d3d54193","parentIds":["6f0a038fe9a847768922ce2c559f06ec"],"metadata":{"ls_model_type":"llm","ls_provider":"chatgpt","ls_model_name":"gpt-4"},"tags":[]}
{"event":"on_parser_stream","data":{"chunk":{"text":"Why","message":{"role":"ai","content":"Why"},"last":false}},"name":"StrOutputParser","runId":"d1d0efcadd904b2090f4d202003d4c04","parentIds":["0e21f6ad4fc84c16a50b3db3d3d54193"],"metadata":{},"tags":[]}
{"event":"on_chain_stream","data":{"chunk":{"text":"Why","message":{"role":"ai","content":"Why"},"last":false}},"name":"ChainActor","runId":"d1d0efcadd904b2090f4d202003d4c04","parentIds":["d1d0efcadd904b2090f4d202003d4c04"],"metadata":{},"tags":[]}
......
```
### Filter flow events

Events can be filtered based on the name, type, or label of the component

```java
public void EventFilterDemo() throws TimeoutException {

    ChatOllama model = ChatOllama.builder().model("llama3:8b").build();

    FlowInstance chain = chainActor.builder()
            .next(model.withConfig(Map.of("run_name", "model")))
            .next((new JsonOutputParser()).withConfig(Map.of("run_name", "my_parser", "tags", List.of("my_chain"))))
            .build();

    EventMessageChunk chunk = chainActor.streamEvent(chain,"Generate JSON data.");
    while (chunk.getIterator().hasNext()) {
        System.out.println(chunk.getIterator().next().toJson());
    }

    System.out.println("\n----------------\n");

    EventMessageChunk chunkFilterByName = chainActor.streamEvent(chain,"Generate JSON data.", event -> List.of("my_parser").contains(event.getName()));
    while (chunkFilterByName.getIterator().hasNext()) {
        System.out.println(chunkFilterByName.getIterator().next().toJson());
    }

    System.out.println("\n----------------\n");

    EventMessageChunk chunkFilterByType = chainActor.streamEvent(chain,"Generate JSON data.", event -> List.of("llm").contains(event.getType()));
    while (chunkFilterByType.getIterator().hasNext()) {
        System.out.println(chunkFilterByType.getIterator().next().toJson());
    }

    System.out.println("\n----------------\n");

    EventMessageChunk chunkFilterByTag = chainActor.streamEvent(chain,"Generate JSON data.", event -> Stream.of("my_chain").anyMatch(event.getTags()::contains));
    while (chunkFilterByTag.getIterator().hasNext()) {
        System.out.println(chunkFilterByTag.getIterator().next().toJson());
    }
}
```
