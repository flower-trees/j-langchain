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

package org.salt.jlangchain.core.llm;

import org.apache.commons.lang3.StringUtils;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.salt.function.flow.FlowEngine;
import org.salt.jlangchain.TestApplication;
import org.salt.jlangchain.core.llm.openai.ChatOpenAI;
import org.salt.jlangchain.core.message.AIMessageChunk;
import org.salt.jlangchain.utils.SpringContextUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.junit4.SpringRunner;

import java.util.concurrent.TimeoutException;

@RunWith(SpringRunner.class)
@SpringBootTest(classes = TestApplication.class)
@SpringBootConfiguration
public class ChatOpenAITest {

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

        ChatOpenAI prompt = new ChatOpenAI();

        AIMessageChunk result = prompt.stream("who are you? give me 3 words.");

        System.out.println(result.toJson());

        while (result.getIterator().hasNext()) {
            AIMessageChunk chunk = null;
            try {
                chunk = result.getIterator().next();
            } catch (TimeoutException e) {
                throw new RuntimeException(e);
            }
            System.out.println("chunk: " + chunk.toJson());
        }
    }

    @Test
    public void streamWordTest() {

        ChatOpenAI prompt = new ChatOpenAI();

        AIMessageChunk result = prompt.stream("who are you? give me 3 words.");

        StringBuilder sb = new StringBuilder();

        while (result.getIterator().hasNext()) {
            AIMessageChunk chunk = null;
            try {
                chunk = result.getIterator().next();
            } catch (TimeoutException e) {
                throw new RuntimeException(e);
            }
            if (StringUtils.isNotEmpty(chunk.getContent())) {
                sb.append(chunk.getContent());
                System.out.println("answer:" + sb);
            }
        }
    }

}
