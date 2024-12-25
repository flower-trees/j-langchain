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
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.salt.function.flow.FlowEngine;
import org.salt.function.flow.FlowInstance;
import org.salt.function.flow.Info;
import org.salt.function.flow.context.ContextBus;
import org.salt.function.flow.node.IResult;
import org.salt.jlangchain.TestApplication;
import org.salt.jlangchain.core.BaseRunnable;
import org.salt.jlangchain.core.ChainActor;
import org.salt.jlangchain.core.llm.ollama.ChatOllama;
import org.salt.jlangchain.core.message.AIMessage;
import org.salt.jlangchain.core.parser.StrOutputParser;
import org.salt.jlangchain.core.parser.generation.ChatGeneration;
import org.salt.jlangchain.core.parser.generation.Generation;
import org.salt.jlangchain.core.prompt.chat.ChatPromptTemplate;
import org.salt.jlangchain.core.prompt.string.PromptTemplate;
import org.salt.jlangchain.core.prompt.value.ChatPromptValue;
import org.salt.jlangchain.core.prompt.value.StringPromptValue;
import org.salt.jlangchain.utils.JsonUtil;
import org.salt.jlangchain.utils.SpringContextUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.junit4.SpringRunner;

import java.util.List;
import java.util.Map;

@RunWith(SpringRunner.class)
@SpringBootTest(classes = TestApplication.class)
@SpringBootConfiguration
public class ChainBuildDemo {

    @Autowired
    FlowEngine flowEngine;

    @Autowired
    ChainActor chainActor;

    @Autowired
    private ApplicationContext context;

    @Before
    public void init() {
        SpringContextUtil.setApplicationContext(context);
    }

    @Test
    public void SimpleDemo() {

        BaseRunnable<StringPromptValue, ?> prompt = PromptTemplate.fromTemplate("tell me a joke about ${topic}");
        ChatOllama oll = ChatOllama.builder().model("qwen2.5:0.5b").build();

        FlowInstance chain = flowEngine.builder().next(prompt).next(oll).next(new StrOutputParser()).build();

        ChatGeneration result = chainActor.invoke(chain, Map.of("topic", "bears"));
        System.out.println(result);
    }

    @Test
    public void ComposeDemo() {

        ChatOllama oll = ChatOllama.builder().model("qwen2.5:0.5b").build();
        StrOutputParser parser = new StrOutputParser();

        BaseRunnable<StringPromptValue, ?> prompt = PromptTemplate.fromTemplate("tell me a joke about ${topic}");
        FlowInstance chain = flowEngine.builder().next(prompt).next(oll).next(parser).build();

        BaseRunnable<StringPromptValue, ?> analysisPrompt = PromptTemplate.fromTemplate("is this a funny joke? ${joke}");

        FlowInstance analysisChain = flowEngine.builder()
                .next(chain)
                .next(input -> Map.of("joke", ((Generation)input).getText()))
                .next(analysisPrompt)
                .next(oll)
                .next(parser).build();

        ChatGeneration result = chainActor.invoke(analysisChain, Map.of("topic", "bears"));
        System.out.println(result);
    }

    @Test
    public void ParallelDemo() {
        ChatOllama oll = ChatOllama.builder().model("qwen2.5:0.5b").build();

        BaseRunnable<StringPromptValue, ?> joke = PromptTemplate.fromTemplate("tell me a joke about ${topic}");
        BaseRunnable<StringPromptValue, ?> poem = PromptTemplate.fromTemplate("write a 2-line poem about ${topic}");

        FlowInstance jokeChain = flowEngine.builder().next(joke).next(oll).build();
        FlowInstance poemChain = flowEngine.builder().next(poem).next(oll).build();

        FlowInstance chain = flowEngine.builder().concurrent((IResult<Map<String, String>>) (iContextBus, isTimeout) -> {
            AIMessage jokeResult = iContextBus.getResult(jokeChain.getFlowId());
            AIMessage poemResult = iContextBus.getResult(poemChain.getFlowId());
            return Map.of("joke", jokeResult.getContent(), "poem", poemResult.getContent());
        }, jokeChain, poemChain).build();

        Map<String, String> result = chainActor.invoke(chain, Map.of("topic", "bears"));
        System.out.println(JsonUtil.toJson(result));
    }

    @Test
    public void RouteDemo() {
        ChatOllama oll = ChatOllama.builder().model("qwen2.5:0.5b").build();

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

        FlowInstance chain = flowEngine.builder().next(prompt).next(oll).next(new StrOutputParser()).build();

        FlowInstance langchainChain = flowEngine.builder().next(PromptTemplate.fromTemplate(
                """
                You are an expert in langchain. \
                Always answer questions starting with "As Harrison Chase told me". \
                Respond to the following question:
                
                Question: ${question}
                Answer:
                """
        )).next(ChatOllama.builder().model("qwen2.5:0.5b").build()).build();

        FlowInstance anthropicChain = flowEngine.builder().next(PromptTemplate.fromTemplate(
                """
                You are an expert in anthropic. \
                Always answer questions starting with "As Dario Amodei told me". \
                Respond to the following question:
            
                Question: ${question}
                Answer:
                """
        )).next(ChatOllama.builder().model("qwen2.5:0.5b").build()).build();

        FlowInstance generalChain = flowEngine.builder().next(PromptTemplate.fromTemplate(
                """
                Respond to the following question:
            
                Question: ${question}
                Answer:
                """
        )).next(ChatOllama.builder().model("qwen2.5:0.5b").build()).build();

        FlowInstance fullChain = flowEngine.builder()
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

    @Test
    public void DynamicDemo() {
        ChatOllama oll = ChatOllama.builder().model("qwen2.5:0.5b").build();

        String contextualizeInstructions = """
                Convert the latest user question into a standalone question given the chat history. Don't answer the question, return the question and nothing else (no descriptive text).""";

        BaseRunnable<ChatPromptValue, Object> contextualizePrompt = ChatPromptTemplate.fromMessages(
                List.of(
                        Pair.of("system", contextualizeInstructions),
                        Pair.of("placeholder", "${chatHistory}"),
                        Pair.of("human", "${question}")
                )
        );

        FlowInstance contextualizeQuestion = flowEngine.builder()
                .next(contextualizePrompt)
                .next(oll)
                .next(new StrOutputParser())
                .build();

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

        FlowInstance contextualizeIfNeeded = flowEngine.builder().next(
                Info.c("chatHistory != null", contextualizeQuestion),
                Info.c(input -> Map.of("question", ((Map<String, String>)input).get("question")))
        ).build();

        FlowInstance fullChain = flowEngine.builder()
                .all(
                        (iContextBus, isTimeout) -> Map.of(
                                "question", iContextBus.getResult(contextualizeIfNeeded.getFlowId()).toString(),
                                "context", iContextBus.getResult("fakeRetriever")),
                        Info.c(contextualizeIfNeeded),
                        Info.c(input -> "egypt's population in 2024 is about 111 million").cAlias("fakeRetriever")
                )
                .next(qaPrompt)
                .next(oll)
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
}
