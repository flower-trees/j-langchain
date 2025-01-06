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

import org.apache.commons.lang3.StringUtils;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.salt.function.flow.FlowInstance;
import org.salt.jlangchain.TestApplication;
import org.salt.jlangchain.core.BaseRunnable;
import org.salt.jlangchain.core.ChainActor;
import org.salt.jlangchain.core.event.EventMessageChunk;
import org.salt.jlangchain.core.llm.ollama.ChatOllama;
import org.salt.jlangchain.core.message.AIMessageChunk;
import org.salt.jlangchain.core.parser.FunctionOutputParser;
import org.salt.jlangchain.core.parser.JsonOutputParser;
import org.salt.jlangchain.core.parser.StrOutputParser;
import org.salt.jlangchain.core.parser.generation.ChatGenerationChunk;
import org.salt.jlangchain.core.prompt.string.PromptTemplate;
import org.salt.jlangchain.core.prompt.value.StringPromptValue;
import org.salt.jlangchain.utils.JsonUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.junit4.SpringRunner;

import java.util.*;
import java.util.concurrent.TimeoutException;
import java.util.stream.Stream;

@RunWith(SpringRunner.class)
@SpringBootTest(classes = TestApplication.class)
@SpringBootConfiguration
public class ChainExtDemo {

    @Autowired
    ChainActor chainActor;

    @Test
    public void StreamDemo() throws TimeoutException, InterruptedException {

        ChatOllama llm = ChatOllama.builder().model("qwen2.5:0.5b").build();

        AIMessageChunk chunk = llm.stream("what color is the sky?, limit 10 words");
        StringBuilder sb = new StringBuilder();
        while (chunk.getIterator().hasNext()) {
            sb.append(chunk.getIterator().next().getContent()).append("|");
            System.out.println(sb);
            Thread.sleep(100);
        }
    }

    @Test
    public void ChainStreamDemo() throws TimeoutException, InterruptedException {

        BaseRunnable<StringPromptValue, ?> prompt = PromptTemplate.fromTemplate("tell me a joke about ${topic}");
        ChatOllama llm = ChatOllama.builder().model("qwen2.5:0.5b").build();

        FlowInstance chain = chainActor.builder().next(prompt).next(llm).next(new StrOutputParser()).build();

        ChatGenerationChunk chunk = chainActor.stream(chain, Map.of("topic", "bears"));
        StringBuilder sb = new StringBuilder();
        while (chunk.getIterator().hasNext()) {
            sb.append(chunk.getIterator().next()).append("|");
            System.out.println();
            Thread.sleep(100);
        }
    }

    @Test
    public void InputDemo() throws TimeoutException, InterruptedException {
        ChatOllama llm = ChatOllama.builder().model("qwen2.5:0.5b").build();
        FlowInstance chain = chainActor.builder().next(llm).next(new JsonOutputParser()).build();
        ChatGenerationChunk chunk = chainActor.stream(chain, "output a list of countries and their populations in JSON format. limit 3 countries.");
        while (chunk.getIterator().hasNext()) {
            System.out.println(chunk.getIterator().next());
            Thread.sleep(100);
        }
    }

    @Test
    public void OutputFunctionDemo() throws TimeoutException, InterruptedException {
        ChatOllama llm = ChatOllama.builder().model("qwen2.5:0.5b").build();

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
                Thread.sleep(100);
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

    @Test
    public void EventDemo() {
        ChatOllama model = ChatOllama.builder().model("qwen2.5:0.5b").build();

        List<EventMessageChunk> events = new ArrayList<>();
        EventMessageChunk chunk = model.streamEvent("hello");
        while (chunk.getIterator().hasNext()) {
            try {
                events.add(chunk.getIterator().next());
            } catch (TimeoutException e) {
                throw new RuntimeException(e);
            }
        }
        events.subList(events.size()-3, events.size()).forEach(event -> System.out.println(event.toJson()));
    }

    @Test
    public void EventChainDemo() {
        BaseRunnable<StringPromptValue, ?> prompt = PromptTemplate.fromTemplate("tell me a joke about ${topic}");

        ChatOllama oll = ChatOllama.builder().model("qwen2.5:0.5b").build();

        FlowInstance chain = chainActor.builder().next(prompt).next(oll).next(new StrOutputParser()).build();

        EventMessageChunk chunk = chainActor.streamEvent(chain, Map.of("topic", "dog"));

        while (chunk.getIterator().hasNext()) {
            try {
                System.out.println(chunk.getIterator().next().toJson());
            } catch (TimeoutException e) {
                throw new RuntimeException(e);
            }
        }
    }

    @Test
    public void EventFilterDemo() {
        ChatOllama model = ChatOllama.builder().model("qwen2.5:0.5b").build();

        FlowInstance chain = chainActor.builder()
                .next(model.withConfig(Map.of("run_name", "model")))
                .next((new JsonOutputParser()).withConfig(Map.of("run_name", "my_parser", "tags", List.of("my_chain"))))
                .build();

        EventMessageChunk chunk = chainActor.streamEvent(chain,"Generate JSON data.");
        while (chunk.getIterator().hasNext()) {
            try {
                System.out.println(chunk.getIterator().next().toJson());
            } catch (TimeoutException e) {
                throw new RuntimeException(e);
            }
        }

        System.out.println("\n----------------\n");

        EventMessageChunk chunkFilterByName = chainActor.streamEvent(chain,"Generate JSON data.", event -> List.of("my_parser").contains(event.getName()));
        while (chunkFilterByName.getIterator().hasNext()) {
            try {
                System.out.println(chunkFilterByName.getIterator().next().toJson());
            } catch (TimeoutException e) {
                throw new RuntimeException(e);
            }
        }

        System.out.println("\n----------------\n");

        EventMessageChunk chunkFilterByType = chainActor.streamEvent(chain,"Generate JSON data.", event -> List.of("llm").contains(event.getType()));
        while (chunkFilterByType.getIterator().hasNext()) {
            try {
                System.out.println(chunkFilterByType.getIterator().next().toJson());
            } catch (TimeoutException e) {
                throw new RuntimeException(e);
            }
        }

        System.out.println("\n----------------\n");

        EventMessageChunk chunkFilterByTag = chainActor.streamEvent(chain,"Generate JSON data.", event -> Stream.of("my_chain").anyMatch(event.getTags()::contains));
        while (chunkFilterByTag.getIterator().hasNext()) {
            try {
                System.out.println(chunkFilterByTag.getIterator().next().toJson());
            } catch (TimeoutException e) {
                throw new RuntimeException(e);
            }
        }
    }
}
