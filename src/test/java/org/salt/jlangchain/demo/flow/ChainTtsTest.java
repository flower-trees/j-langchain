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

import org.junit.Test;
import org.junit.runner.RunWith;
import org.salt.function.flow.FlowInstance;
import org.salt.jlangchain.TestApplication;
import org.salt.jlangchain.core.BaseRunnable;
import org.salt.jlangchain.core.ChainActor;
import org.salt.jlangchain.core.llm.ollama.ChatOllama;
import org.salt.jlangchain.core.parser.StrOutputParser;
import org.salt.jlangchain.core.parser.generation.ChatGeneration;
import org.salt.jlangchain.core.prompt.string.PromptTemplate;
import org.salt.jlangchain.core.prompt.value.StringPromptValue;
import org.salt.jlangchain.core.tts.card.TtsCardChunk;
import org.salt.jlangchain.core.tts.doubao.DoubaoTts;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.junit4.SpringRunner;

import java.util.Map;
import java.util.concurrent.TimeoutException;

@RunWith(SpringRunner.class)
@SpringBootTest(classes = TestApplication.class)
@SpringBootConfiguration
public class ChainTtsTest {

    @Autowired
    ChainActor chainActor;

    @Test
    public void TtsInvoke() {

        BaseRunnable<StringPromptValue, ?> prompt = PromptTemplate.fromTemplate("tell me a joke about ${topic}");
        ChatOllama llm = ChatOllama.builder().model("qwen2.5:0.5b").build();

        FlowInstance chain = chainActor.builder().next(prompt).next(llm).next(new StrOutputParser()).next(new DoubaoTts()).build();

        ChatGeneration result = chainActor.invoke(chain, Map.of("topic", "bears"));
        System.out.println(result);
    }

    @Test
    public void TtsStream() throws TimeoutException {
        BaseRunnable<StringPromptValue, ?> prompt = PromptTemplate.fromTemplate("tell me a joke about ${topic}");
        ChatOllama llm = ChatOllama.builder().model("qwen2.5:0.5b").build();
        FlowInstance chain = chainActor.builder().next(prompt).next(llm).next(new StrOutputParser()).next(new DoubaoTts()).build();
        TtsCardChunk result = chainActor.stream(chain, Map.of("topic", "bears"));
        StringBuilder sb = new StringBuilder();

        while (result.getIterator().hasNext()) {
            TtsCardChunk chunk = result.getIterator().next();
            if (!chunk.isTts()) {
                sb.append(chunk.getText());
                System.out.println("answer:" + sb);
            } else {
                System.out.println("card:" + chunk.getText());
            }
        }
    }
}
