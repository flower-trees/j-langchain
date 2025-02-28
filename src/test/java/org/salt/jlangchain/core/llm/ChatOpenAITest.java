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

import org.junit.Test;
import org.junit.runner.RunWith;
import org.salt.jlangchain.TestApplication;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.junit4.SpringRunner;

import java.util.concurrent.TimeoutException;

@RunWith(SpringRunner.class)
@SpringBootTest(classes = TestApplication.class)
@SpringBootConfiguration
public class ChatOpenAITest {

    @Test
    public void streamTest() throws TimeoutException {

//        ChatOpenAI prompt = new ChatOpenAI();
//
//        AIMessageChunk result = prompt.stream("who are you? give me 3 words.");
//
//        System.out.println(result.toJson());
//
//        while (result.getIterator().hasNext()) {
//            AIMessageChunk chunk = result.getIterator().next();
//            System.out.println("chunk: " + chunk.toJson());
//        }
    }

    @Test
    public void streamWordTest() throws TimeoutException {

//        ChatOpenAI prompt = new ChatOpenAI();
//
//        AIMessageChunk result = prompt.stream("who are you? give me 3 words.");
//
//        StringBuilder sb = new StringBuilder();
//
//        while (result.getIterator().hasNext()) {
//            AIMessageChunk chunk = result.getIterator().next();;
//            if (StringUtils.isNotEmpty(chunk.getContent())) {
//                sb.append(chunk.getContent());
//                System.out.println("answer:" + sb);
//            }
//        }
    }

}
