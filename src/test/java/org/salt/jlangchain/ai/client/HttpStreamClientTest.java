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
import org.salt.jlangchain.ai.chat.strategy.ListenerStrategy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.junit4.SpringRunner;

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
    public void chatgptStream() {

        String url = "https://api.openai.com/v1/chat/completions";

        Map<String, ?> body = Map.of(
                "model", "gpt-3.5-turbo",
                "messages", List.of(Map.of("role", "user", "content", "Hello, how are you?")),
                "stream", true);

        String key = System.getenv("CHATGPT_KEY");
        Map<String, String> headers = Map.of(
                "Content-Type", "application/json",
                "Authorization", "Bearer " + key);

        chatGPTHttpClient.stream(url, body, headers, List.of(new ListenerStrategyTest()));
    }

    @Test
    public void doubaoStream() {

        String url = "https://ark.cn-beijing.volces.com/api/v3/chat/completions";

        Map<String, ?> body = Map.of(
                "model", "ep-20240611104225-2d4ww",
                "messages", List.of(Map.of("role", "system", "content", "You are a weather forecast assistant"), Map.of("role", "user", "content", "What's the weather like in Beijing today")),
                "stream", true);

        String key = System.getenv("DOUBAO_KEY");

        Map<String, String> headers = Map.of(
                "Content-Type", "application/json",
                "Authorization", "Bearer " + key);

        commonHttpClient.stream(url, body, headers, List.of(new ListenerStrategyTest()));
    }

    @Test
    public void qwenStream() {

        String url = "https://dashscope.aliyuncs.com/api/v1/services/aigc/text-generation/generation";

        Map<String, ?> body = Map.of(
                "model", "qwq-32b-preview",
                    "input", Map.of("messages",
                                    List.of(Map.of("role", "system", "content", "You are a weather forecast assistant"),
                                            Map.of("role", "user", "content", "What's the weather like in Beijing today"))),
                "stream", true);

        String key = System.getenv("ALIYUN_KEY");

        Map<String, String> headers = Map.of(
                "Content-Type", "application/json",
                "Authorization", "Bearer " + key,
                "X-DashScope-SSE", "enable"
                );

        commonHttpClient.stream(url, body, headers, List.of(new ListenerStrategyTest()));
    }

    @Test
    public void qwenOpenAIStream() {

        String url = "https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions";

        Map<String, ?> body = Map.of(
                "model", "qwq-32b-preview",
                "messages", List.of(Map.of("role", "system", "content", "You are a weather forecast assistant"), Map.of("role", "user", "content", "What's the weather like in Beijing today")),
                "stream", true);

        String key = System.getenv("ALIYUN_KEY");

        Map<String, String> headers = Map.of(
                "Content-Type", "application/json",
                "Authorization", "Bearer " + key
        );

        commonHttpClient.stream(url, body, headers, List.of(new ListenerStrategyTest()));
    }

    @Test
    public void qwenOpenAIRequest() {

        String url = "https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions";

        Map<String, ?> body = Map.of(
                "model", "qwq-32b-preview",
                "messages", List.of(Map.of("role", "system", "content", "You are a weather forecast assistant"), Map.of("role", "user", "content", "What's the weather like in Beijing today")),
                "stream", true);

        String key = System.getenv("ALIYUN_KEY");

        Map<String, String> headers = Map.of(
                "Content-Type", "application/json",
                "Authorization", "Bearer " + key
        );

        String result = commonHttpClient.request(url, body, headers, String.class);
        System.out.println(result);
    }

    static class ListenerStrategyTest implements ListenerStrategy {
        public void onMessage(String msg) {
            System.out.println(msg);
        }
    }
}