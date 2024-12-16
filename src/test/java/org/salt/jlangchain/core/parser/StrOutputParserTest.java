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

package org.salt.jlangchain.core.parser;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.salt.function.flow.FlowEngine;
import org.salt.jlangchain.TestApplication;
import org.salt.jlangchain.core.message.AIMessageChunk;
import org.salt.jlangchain.core.message.FinishReasonType;
import org.salt.jlangchain.core.parser.generation.ChatGenerationChunk;
import org.salt.jlangchain.utils.SpringContextUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.context.junit4.SpringRunner;

import java.util.concurrent.TimeoutException;

@RunWith(SpringRunner.class)
@SpringBootTest(classes = TestApplication.class)
@SpringBootConfiguration
public class StrOutputParserTest {

    @Autowired
    FlowEngine flowEngine;

    @Autowired
    private ApplicationContext context;

    @Before
    public void init() {
        SpringContextUtil.setApplicationContext(context);
    }

    @Test
    public void streamTest() {

        StrOutputParser parser = new StrOutputParser();

        ChatGenerationChunk result = parser.stream("who are you? give me 3 words.");

        while (result.getIterator().hasNext()) {
            try {
                ChatGenerationChunk chunk = result.getIterator().next();
                System.out.println("chunk: " + chunk);
            } catch (TimeoutException e) {
                throw new RuntimeException(e);
            }
        }
    }

    @Test
    public void streamBaseMessageTest() {

        StrOutputParser parser = new StrOutputParser();

        AIMessageChunk aiMessageChunk = new AIMessageChunk();

        SpringContextUtil.getApplicationContext().getBean(ThreadPoolTaskExecutor.class).execute(
                () -> {
                    String stringPrompt = "who are you? give me 3 words.";
                    String[] words = stringPrompt.split("\\s+");
                    for (String word : words) {
                        AIMessageChunk chunk = AIMessageChunk.builder().content(word).build();
                        if (word.contains(".")) {
                            chunk.setFinishReason(FinishReasonType.STOP.getCode());
                        }
                        try {
                            aiMessageChunk.getIterator().append(chunk);
                        } catch (TimeoutException e) {
                            throw new RuntimeException(e);
                        }
                    }
                }
        );

        ChatGenerationChunk result = parser.stream(aiMessageChunk);

        while (result.getIterator().hasNext()) {
            try {
                ChatGenerationChunk chunk = result.getIterator().next();
                System.out.println("chunk: " + chunk);
            } catch (TimeoutException e) {
                throw new RuntimeException(e);
            }
        }
    }

}
