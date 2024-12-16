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

package org.salt.jlangchain.ai.client.stream;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import okio.BufferedSource;
import org.apache.commons.lang3.StringUtils;
import org.salt.jlangchain.ai.client.AiException;
import org.salt.jlangchain.ai.strategy.ListenerStrategy;
import org.salt.jlangchain.utils.JsonUtil;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.scheduling.annotation.Async;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Slf4j
@Data
public class HttpStreamClient implements InitializingBean {

    private OkHttpClient okHttpClient;

    private long sleepTime = 10;
    private int maxConnections = 200;
    private int maxConnectionsPerHost = 200;
    private int maxIdleConnections = 200;
    private int keepAliveDuration = 10;
    private TimeUnit keepAliveTimeUnit = TimeUnit.MINUTES;
    private long connectTimeout = 30000;
    private long readTimeout = 30000;
    private long writeTimeout = 30000;
    private TimeUnit connectTimeUnit = TimeUnit.SECONDS;
    private String proxyHost;
    private int proxyPort;

    @Override
    public void afterPropertiesSet() {
        ConnectionPool connectionPool = new ConnectionPool(maxIdleConnections, keepAliveDuration, keepAliveTimeUnit);
        OkHttpClient.Builder builder = new OkHttpClient.Builder()
                .connectionPool(connectionPool)
                .connectTimeout(connectTimeout, connectTimeUnit)
                .readTimeout(readTimeout, connectTimeUnit)
                .writeTimeout(writeTimeout, connectTimeUnit);
        if (StringUtils.isNotBlank(proxyHost)) {
            Proxy proxy = new Proxy(Proxy.Type.HTTP, new InetSocketAddress(proxyHost, proxyPort));
            builder.proxy(proxy);
        }
        okHttpClient = builder.build();
        okHttpClient.dispatcher().setMaxRequests(maxConnections);
        okHttpClient.dispatcher().setMaxRequestsPerHost(maxConnectionsPerHost);
    }

    @Async
    public <T> void call(String url, T body, Map<String, String> headers, List<ListenerStrategy> strategyList) {
        request(url, body, headers, strategyList);
    }

    public <T> void request(String url, T body, Map<String, String> headers, List<ListenerStrategy> strategyList) {

        log.debug("http stream call start");
        strategyList.forEach(ListenerStrategy::onInit);

        String headersJson = JsonUtil.toJson(headers);
        String bodyJson;
        if (body instanceof String) {
            bodyJson = (String) body;
        } else {
            bodyJson = JsonUtil.toJson(body);
        }

        assert bodyJson != null;
        Request request = new Request.Builder()
                .url(url)
                .headers(Headers.of(headers))
                .post(RequestBody.create(MediaType.parse("application/json; charset=utf-8"), bodyJson))
                .build();

        log.debug("http stream call, url:{}, headers:{}, body:{}", url, headersJson, JsonUtil.toJson(body));

        try {
            Response response = okHttpClient.newCall(request).execute();
            if (response.isSuccessful()) {

                log.debug("http stream call open");
                openForEach(strategyList);

                ResponseBody responseBody = response.body();
                if (responseBody != null) {
                    BufferedSource source = responseBody.source();
                    while (!source.exhausted()) {
                        String lineComplete = source.readUtf8LineStrict();

                        if (StringUtils.isBlank(lineComplete.trim())) {
                            continue;
                        }

                        log.debug("http stream call read, data: {}", lineComplete);

                        if (lineComplete.startsWith("data:")) {
                            String content = getDateContent(lineComplete);
                            dealContent(content, strategyList);
                        } else {
                            dealContent(lineComplete, strategyList);
                        }
                    }
                    responseBody.close();
                }
            } else {
                log.error("http stream call fail, e:response code is {}", response.code());
                errorForEach(strategyList, new AiException(response.code(), JsonUtil.toJson(response)));
            }
        } catch (IOException e) {
            log.error("http stream call io error, e:", e);
            errorForEach(strategyList, e);
        } finally {
            completeForEach(strategyList);
        }
    }

    private String getDateContent(String lineComplete) {
        int index = "data:".length();
        if (lineComplete.startsWith("data: ")) {
            index += 1;
        }
        return lineComplete.substring(index);
    }

    private void dealContent(String content, List<ListenerStrategy> strategyList) {
        if (!StringUtils.equalsIgnoreCase(content, "[DONE]")) {
            messageForEach(strategyList, content);
            pause();
        } else {
            closeForEach(strategyList);
        }
    }

    private void openForEach(List<ListenerStrategy> strategyList) {
        strategyList.forEach(strategy -> {
            try {
                strategy.onOpen();
            } catch (Exception e) {
                log.error("http stream call onOpen error, e", e);
            }
        });
    }

    private void messageForEach(List<ListenerStrategy> strategyList, String content) {
        strategyList.forEach(strategy -> {
            try {
                strategy.onMessage(content);
            } catch (Exception e) {
                log.error("http stream call onMessage error, e", e);
            }
        });
    }

    private void closeForEach(List<ListenerStrategy> strategyList) {
        strategyList.forEach(strategy -> {
            try {
                strategy.onClosed();
            } catch (Exception e) {
                log.error("http stream call onClosed error, e", e);
            }
        });
    }

    private void errorForEach(List<ListenerStrategy> strategyList, Throwable t) {
        strategyList.forEach(strategy -> {
            try {
                strategy.onError(t);
            } catch (Exception e) {
                log.error("http stream call onError error, e", e);
            }
        });
    }

    private void completeForEach(List<ListenerStrategy> strategyList) {
        strategyList.forEach(strategy -> {
            try {
                strategy.onComplete();
            } catch (Exception e) {
                log.error("http stream call onComplete error, e", e);
            }
        });
    }

    private void pause() {
        try {
            Thread.sleep(sleepTime);
        } catch (InterruptedException e) {
            log.warn("http stream call pause, e:{}", e.getMessage());
        }
    }
}
