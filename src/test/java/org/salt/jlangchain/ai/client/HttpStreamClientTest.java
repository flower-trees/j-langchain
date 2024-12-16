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

package org.salt.jlangchain.ai.client;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.salt.jlangchain.TestApplication;
import org.salt.jlangchain.ai.client.stream.HttpStreamClient;
import org.salt.jlangchain.ai.strategy.ListenerStrategy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.junit4.SpringRunner;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RunWith(SpringRunner.class)
@SpringBootTest(classes = TestApplication.class)
@SpringBootConfiguration
public class HttpStreamClientTest {

    @Autowired
    HttpStreamClient chatGPTHttpClient;

    @Autowired
    HttpStreamClient commonHttpClient;

    @Test
    public void chatgptCall() {

        String url = "https://api.openai.com/v1/chat/completions";

        String body = "{\n" +
                "    \"model\": \"gpt-3.5-turbo\",\n" +
                "    \"messages\": [{\"role\": \"user\", \"content\": \"Hello, how are you?\"}],\n" +
                "    \"stream\": true\n" +
                "  }";

        String key = System.getenv("CHATGPT_KEY");
        Map<String, String> headers = new HashMap<>();
        headers.put("Content-Type", "application/json");
        headers.put("Authorization", "Bearer " + key);

        chatGPTHttpClient.call(url, body, headers, List.of(new ListenerStrategyTest()));
    }

    @Test
    public void doubaoCall() {

        String url = "https://ark.cn-beijing.volces.com/api/v3/chat/completions";

        String body = "{\n" +
                "    \"model\": \"ep-20240611104225-2d4ww\",\n" +
                "    \"messages\": [\n" +
                "      {\n" +
                "        \"role\": \"system\",\n" +
                "        \"content\": \"你是一个天气预报助手\"\n" +
                "      },\n" +
                "      {\n" +
                "        \"role\": \"user\",\n" +
                "        \"content\": \"今天北京天气怎么样\"\n" +
                "      }\n" +
                "    ],\n" +
                "    \"stream\": true\n" +
                "  }";

        String key = System.getenv("DOUBAO_KEY");
        Map<String, String> headers = new HashMap<>();
        headers.put("Content-Type", "application/json");
        headers.put("Authorization", "Bearer " + key);

        commonHttpClient.call(url, body, headers, List.of(new ListenerStrategyTest()));
    }

    static class ListenerStrategyTest implements ListenerStrategy {

        public void onMessage(String msg) {
            System.out.println(msg);
        }
    }
}