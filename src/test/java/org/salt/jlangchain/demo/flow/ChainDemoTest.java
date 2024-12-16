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
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.salt.function.flow.FlowEngine;
import org.salt.function.flow.FlowInstance;
import org.salt.jlangchain.TestApplication;
import org.salt.jlangchain.core.BaseRunnable;
import org.salt.jlangchain.core.ChainActor;
import org.salt.jlangchain.core.llm.ollama.ChatOllama;
import org.salt.jlangchain.core.parser.StrOutputParser;
import org.salt.jlangchain.core.parser.generation.ChatGenerationChunk;
import org.salt.jlangchain.core.prompt.chat.ChatPromptTemplate;
import org.salt.jlangchain.core.prompt.value.ChatPromptValue;
import org.salt.jlangchain.utils.SpringContextUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.junit4.SpringRunner;

import java.util.Map;
import java.util.concurrent.TimeoutException;

@RunWith(SpringRunner.class)
@SpringBootTest(classes = TestApplication.class)
@SpringBootConfiguration
public class ChainDemoTest {

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
    public void ChainDemo() {

        BaseRunnable<ChatPromptValue, ?> prompt = ChatPromptTemplate.fromTemplate("tell me a joke about ${topic}");

        ChatOllama oll = ChatOllama.builder().model("qwen2.5:0.5b").build();

        StrOutputParser parser = new StrOutputParser();

        FlowInstance chain = flowEngine.builder().next(prompt).next(oll).next(parser).build();

        ChatGenerationChunk result = chainActor.stream(chain, Map.of("topic", "dog"));

        StringBuilder sb = new StringBuilder();

        while (result.getIterator().hasNext()) {
            ChatGenerationChunk chunk = null;
            try {
                chunk = result.getIterator().next();
            } catch (TimeoutException e) {
                throw new RuntimeException(e);
            }
            if (StringUtils.isNotEmpty(chunk.toString())) {
                sb.append(chunk);
                System.out.println("answer:" + sb);
            }
        }
    }
}
