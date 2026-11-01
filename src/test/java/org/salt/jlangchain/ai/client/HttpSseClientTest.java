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

import com.sun.net.httpserver.HttpServer;
import org.junit.Test;
import org.salt.jlangchain.ai.chat.sse.SseListenerStrategy;
import org.salt.jlangchain.ai.client.stream.HttpSseClient;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;

public class HttpSseClientTest {

    @Test
    public void parsesGenericSseEvents() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/events", exchange -> {
            byte[] body = ("event: chunk\n"
                    + "data: hello\n\n"
                    + "data: [DONE]\n\n").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();

        try {
            HttpSseClient client = new HttpSseClient(null);
            client.afterPropertiesSet();
            List<String> lifecycle = new ArrayList<>();
            List<String> messages = new ArrayList<>();
            SseListenerStrategy listener = new SseListenerStrategy() {
                @Override
                public void onOpen() {
                    lifecycle.add("open");
                }

                @Override
                public void onMessage(String event, String message) {
                    messages.add(event + ":" + message);
                }

                @Override
                public void onClosed() {
                    lifecycle.add("closed");
                }

                @Override
                public void onComplete() {
                    lifecycle.add("complete");
                }
            };

            client.stream(
                    "http://localhost:" + server.getAddress().getPort() + "/events",
                    Map.of("input", "hello"),
                    Map.of("Content-Type", "application/json"),
                    List.of(listener)
            );

            assertEquals(List.of("chunk:hello"), messages);
            assertEquals(List.of("open", "closed", "complete"), lifecycle);
        } finally {
            server.stop(0);
        }
    }
}
