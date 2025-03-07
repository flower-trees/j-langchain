/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.salt.jlangchain.demo.flow;

import org.apache.commons.lang3.tuple.Pair;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.salt.function.flow.FlowInstance;
import org.salt.function.flow.Info;
import org.salt.function.flow.context.ContextBus;
import org.salt.jlangchain.TestApplication;
import org.salt.jlangchain.core.BaseRunnable;
import org.salt.jlangchain.core.ChainActor;
import org.salt.jlangchain.core.InvokeChain;
import org.salt.jlangchain.core.llm.ollama.ChatOllama;
import org.salt.jlangchain.core.llm.openai.ChatOpenAI;
import org.salt.jlangchain.core.message.AIMessageChunk;
import org.salt.jlangchain.core.parser.StrOutputParser;
import org.salt.jlangchain.core.parser.generation.ChatGenerationChunk;
import org.salt.jlangchain.core.parser.generation.Generation;
import org.salt.jlangchain.core.prompt.chat.ChatPromptTemplate;
import org.salt.jlangchain.core.prompt.string.PromptTemplate;
import org.salt.jlangchain.core.prompt.value.ChatPromptValue;
import org.salt.jlangchain.core.prompt.value.StringPromptValue;
import org.salt.jlangchain.utils.JsonUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.junit4.SpringRunner;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeoutException;

@RunWith(SpringRunner.class)
@SpringBootTest(classes = TestApplication.class)
@SpringBootConfiguration
public class ChainBuildStreamDemo {

    @Autowired
    ChainActor chainActor;

    @Test
    public void SimpleDemo() throws TimeoutException {

        BaseRunnable<StringPromptValue, ?> prompt = PromptTemplate.fromTemplate("tell me a joke about ${topic}");
        ChatOllama llm = ChatOllama.builder().model("qwen2.5:0.5b").build();

        FlowInstance chain = chainActor.builder().next(prompt).next(llm).next(new StrOutputParser()).build();

        ChatGenerationChunk chunk = chainActor.stream(chain, Map.of("topic", "bears"));

        StringBuilder sb = new StringBuilder();
        while (chunk.getIterator().hasNext()) {
            sb.append(chunk.getIterator().next());
            System.out.println(sb);
        }
    }

    @Test
    public void SwitchDemo() throws TimeoutException {

        BaseRunnable<StringPromptValue, ?> prompt = PromptTemplate.fromTemplate("tell me a joke about ${topic}");
        ChatOllama chatOllama = ChatOllama.builder().model("qwen2.5:0.5b").build();
        ChatOpenAI chatOpenAI = ChatOpenAI.builder().model("gpt-4").build();

        FlowInstance chain = chainActor.builder()
                .next(prompt)
                .next(
                    Info.c("vendor == 'ollama'", chatOllama),
                    Info.c("vendor == 'chatgpt'", chatOpenAI),
                    Info.c(input -> "sorry, I don't know how to do that")
                )
                .next(new StrOutputParser()).build();

        ChatGenerationChunk chunk = chainActor.stream(chain, Map.of("topic", "bears", "vendor", "ollama"));

        StringBuilder sb = new StringBuilder();
        while (chunk.getIterator().hasNext()) {
            sb.append(chunk.getIterator().next());
            System.out.println(sb);
        }
    }

    @Test
    public void ComposeDemo() throws TimeoutException {

        ChatOllama llm = ChatOllama.builder().model("qwen2.5:0.5b").build();
        StrOutputParser parser = new StrOutputParser();

        BaseRunnable<StringPromptValue, ?> prompt = PromptTemplate.fromTemplate("tell me a joke about ${topic}");
        FlowInstance chain = chainActor.builder().next(prompt).next(llm).next(parser).build();

        BaseRunnable<StringPromptValue, ?> analysisPrompt = PromptTemplate.fromTemplate("is this a funny joke? ${joke}");

        FlowInstance analysisChain = chainActor.builder()
                .next(new InvokeChain(chain))
                .next(input -> { System.out.printf("joke content: '%s' \n\n", input); return input; })
                .next(input -> Map.of("joke", ((Generation)input).getText()))
                .next(analysisPrompt)
                .next(llm)
                .next(parser).build();

        ChatGenerationChunk chunk = chainActor.stream(analysisChain, Map.of("topic", "bears"));
        StringBuilder sb = new StringBuilder();
        while (chunk.getIterator().hasNext()) {
            sb.append(chunk.getIterator().next());
            System.out.println(sb);
        }
    }

    @Test
    public void ParallelDemo() {

        BaseRunnable<StringPromptValue, ?> joke = PromptTemplate.fromTemplate("tell me a joke about ${topic}");
        BaseRunnable<StringPromptValue, ?> poem = PromptTemplate.fromTemplate("write a 2-line poem about ${topic}");

        FlowInstance jokeChain = chainActor.builder().next(joke).next(ChatOllama.builder().model("qwen2.5:0.5b").build()).build();
        FlowInstance poemChain = chainActor.builder().next(poem).next(ChatOllama.builder().model("qwen2.5:0.5b").build()).build();

        FlowInstance chain = chainActor.builder()
                .concurrent(jokeChain, poemChain)
                .next(input -> {
                    Map<String, Object> map = (Map<String, Object>) input;
                    return Map.of("joke", map.get(jokeChain.getFlowId()), "poem", map.get(poemChain.getFlowId()));
                })
                .build();

        Map<String, AIMessageChunk> result = chainActor.stream(chain, Map.of("topic", "bears"));

        CompletableFuture.runAsync(() -> {
            AIMessageChunk jokeChunk = result.get("joke");
            StringBuilder jokeSb = new StringBuilder().append("joke: ");
            while (true) {
                try {
                    if (!jokeChunk.getIterator().hasNext()) break;
                } catch (TimeoutException e) {
                    throw new RuntimeException(e);
                }
                jokeSb.append(jokeChunk.getIterator().next().getContent());
                System.out.println(jokeSb);
            }
        });

        CompletableFuture.runAsync(() -> {
            AIMessageChunk poemChunk = result.get("poem");
            StringBuilder poemSb = new StringBuilder().append("poem: ");
            while (true) {
                try {
                    if (!poemChunk.getIterator().hasNext()) break;
                } catch (TimeoutException e) {
                    throw new RuntimeException(e);
                }
                poemSb.append(poemChunk.getIterator().next().getContent());
                System.out.println(poemSb);
            }
        }).join();
    }

    @Test
    public void RouteDemo() throws TimeoutException {
        ChatOllama llm = ChatOllama.builder().model("qwen2.5:0.5b").build();

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
        )).next(ChatOllama.builder().model("qwen2.5:0.5b").build()).build();

        FlowInstance anthropicChain = chainActor.builder().next(PromptTemplate.fromTemplate(
                """
                You are an expert in anthropic. \
                Always answer questions starting with "As Dario Amodei told me". \
                Respond to the following question:
            
                Question: ${question}
                Answer:
                """
        )).next(ChatOllama.builder().model("qwen2.5:0.5b").build()).build();

        FlowInstance generalChain = chainActor.builder().next(PromptTemplate.fromTemplate(
                """
                Respond to the following question:
            
                Question: ${question}
                Answer:
                """
        )).next(ChatOllama.builder().model("qwen2.5:0.5b").build()).build();

        FlowInstance fullChain = chainActor.builder()
                .next(new InvokeChain(chain))
                .next(input -> { System.out.printf("topic: '%s' \n\n", input); return input; })
                .next(input -> Map.of("prompt", input, "question", ((Map<?, ?>)ContextBus.get().getFlowParam()).get("question")))
                .next(input -> { System.out.printf("topic: '%s' \n\n", input); return input; })
                .next(
                        Info.c("topic == 'anthropic'", anthropicChain),
                        Info.c("topic == 'langchain'", langchainChain),
                        Info.c(generalChain)
                ).build();

        AIMessageChunk chunk = chainActor.stream(fullChain, Map.of("question", "how do I use Anthropic?"));
        StringBuilder sb = new StringBuilder();
        while (chunk.getIterator().hasNext()) {
            sb.append(chunk.getIterator().next().getContent());
            System.out.println(sb);
        }
    }

    @Test
    public void DynamicDemo() throws TimeoutException {
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
                Info.c("chatHistory != null", new InvokeChain(contextualizeQuestion)),
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
                    Info.c(contextualizeIfNeeded),
                    Info.c(input -> "egypt's population in 2024 is about 111 million").cAlias("fakeRetriever")
                )
                .next(input -> Map.of(
                        "question", ContextBus.get().getResult(contextualizeIfNeeded.getFlowId()).toString(),
                        "context", ContextBus.get().getResult("fakeRetriever")))
                .next(qaPrompt)
                .next(input -> { System.out.printf("topic: '%s' \n\n", JsonUtil.toJson(input)); return input; })
                .next(llm)
                .next(new StrOutputParser())
                .build();

        ChatGenerationChunk chunk = chainActor.stream(fullChain,
                Map.of(
                        "question", "what about egypt",
                        "chatHistory",
                                List.of(
                                        Pair.of("human", "what's the population of indonesia"),
                                        Pair.of("ai", "about 276 million")
                                )
                )
        );
        StringBuilder sb = new StringBuilder();
        while (chunk.getIterator().hasNext()) {
            sb.append(chunk.getIterator().next().getText());
            System.out.println(sb);
        }
    }
}
