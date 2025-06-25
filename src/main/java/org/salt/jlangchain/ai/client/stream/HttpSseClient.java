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
import org.salt.function.flow.context.ContextBus;
import org.salt.function.flow.thread.TheadHelper;
import org.salt.jlangchain.ai.chat.sse.SseListenerStrategy;
import org.salt.jlangchain.ai.client.AiException;
import org.salt.jlangchain.utils.JsonUtil;
import org.springframework.beans.factory.InitializingBean;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Slf4j
@Data
public class HttpSseClient implements InitializingBean {

    private TheadHelper theadHelper;

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

        TrustManager[] trustAllCerts = new TrustManager[]{
                new X509TrustManager() {
                    public void checkClientTrusted(java.security.cert.X509Certificate[] chain, String authType) {}
                    public void checkServerTrusted(java.security.cert.X509Certificate[] chain, String authType) {}
                    public java.security.cert.X509Certificate[] getAcceptedIssuers() { return new java.security.cert.X509Certificate[0]; }
                }
        };
        SSLContext sslContext = null;
        try {
            sslContext = SSLContext.getInstance("TLS");
            sslContext.init(null, trustAllCerts, new java.security.SecureRandom());
        } catch (NoSuchAlgorithmException | KeyManagementException e) {
            throw new RuntimeException(e);
        }

        builder.sslSocketFactory(sslContext.getSocketFactory(), (X509TrustManager) trustAllCerts[0]);
        builder.hostnameVerifier((hostname, session) -> true);

        okHttpClient = builder.build();
        okHttpClient.dispatcher().setMaxRequests(maxConnections);
        okHttpClient.dispatcher().setMaxRequestsPerHost(maxConnectionsPerHost);
    }

    public HttpSseClient(TheadHelper theadHelper) {
        this.theadHelper = theadHelper;
    }

    public <T, R> R request(String url, T body, Map<String, String> headers, Class<R> clazz) {
        log.debug("http request call start");

        Request request = buildRequest(url, body, headers);

        try (Response response = okHttpClient.newCall(request).execute()) {
            if (response.isSuccessful() && response.body() != null) {
                log.debug("http stream request open");
                return JsonUtil.fromJson(response.body().string(), clazz);
            } else {
                log.error("http request call fail, e:response code: {}, msg:{}", response.code(), response.body() != null ? new String(response.body().bytes()) : "");
                throw new RuntimeException("Request failed with code: " + response.code());
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public <T> void astream(String url, T body, Map<String, String> headers, List<SseListenerStrategy> strategyList) {
        theadHelper.submit(() -> stream(url, body, headers, strategyList));
    }

    public <T> void stream(String url, T body, Map<String, String> headers, List<SseListenerStrategy> strategyList) {

        log.debug("http stream call start");
        strategyList.forEach(SseListenerStrategy::onInit);

        Request request = buildRequest(url, body, headers);

        try (Response response = okHttpClient.newCall(request).execute()) {
            if (response.isSuccessful()) {
                log.debug("http stream call open");
                openForEach(strategyList);

                ResponseBody responseBody = response.body();
                if (responseBody != null) {
                    try {
                        BufferedSource source = responseBody.source();
                        String event = null;
                        while (!source.exhausted()) {
                            // is stop call
                            if (ContextBus.get() != null && ((ContextBus) ContextBus.get()).isStopProcess()) {
                                log.info("http stream call stop");
                                dealContent("stop","stop", strategyList);
                                dealContent("done", "[DONE]", strategyList);
                                break;
                            }

                            String lineComplete = source.readUtf8LineStrict();

                            if (StringUtils.isBlank(lineComplete.trim())) {
                                continue;
                            }

                            log.debug("http stream call read, data: {}", lineComplete);

                            if (lineComplete.startsWith("data:")) {
                                String content = getDateContent(lineComplete);
                                dealContent(event, content, strategyList);
                            } else if (lineComplete.startsWith("event:")) {
                                event = getEvent(lineComplete);
                            }
                        }
                    } finally {
                        responseBody.close();
                    }
                }
            } else {
                log.error("http stream call fail, e:response code: {}, msg:{}", response.code(), response.body() != null ? new String(response.body().bytes()) : "");
                errorForEach(strategyList, new AiException(response.code(), JsonUtil.toJson(response)));
            }
        } catch (IOException e) {
            log.error("http stream call io error, e:", e);
            errorForEach(strategyList, e);
        } finally {
            completeForEach(strategyList);
        }
    }

    private <T> Request buildRequest(String url, T body, Map<String, String> headers) {

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

        log.debug("http stream call, url:{}, headers:{}, body:{}", url, headers, JsonUtil.toJson(body));

        return request;
    }

    private String getDateContent(String lineComplete) {
        int index = "data:".length();
        if (lineComplete.startsWith("data: ")) {
            index += 1;
        }
        return lineComplete.substring(index);
    }

    private String getEvent(String lineComplete) {
        int index = "event:".length();
        return lineComplete.substring(index);
    }

    private void dealContent(String event, String content, List<SseListenerStrategy> strategyList) {
        if (!StringUtils.equalsIgnoreCase(content, "[DONE]")) {
            messageForEach(strategyList, event, content);
            pause();
        } else {
            closeForEach(strategyList);
        }
    }

    private void openForEach(List<SseListenerStrategy> strategyList) {
        strategyList.forEach(strategy -> {
            try {
                strategy.onOpen();
            } catch (Exception e) {
                log.error("http stream call onOpen error, e", e);
            }
        });
    }

    private void messageForEach(List<SseListenerStrategy> strategyList, String event, String content) {
        strategyList.forEach(strategy -> {
            try {
                strategy.onMessage(event, content);
            } catch (Exception e) {
                log.error("http stream call onMessage error, e", e);
            }
        });
    }

    private void closeForEach(List<SseListenerStrategy> strategyList) {
        strategyList.forEach(strategy -> {
            try {
                strategy.onClosed();
            } catch (Exception e) {
                log.error("http stream call onClosed error, e", e);
            }
        });
    }

    private void errorForEach(List<SseListenerStrategy> strategyList, Throwable t) {
        strategyList.forEach(strategy -> {
            try {
                strategy.onError(t);
            } catch (Exception e) {
                log.error("http stream call onError error, e", e);
            }
        });
    }

    private void completeForEach(List<SseListenerStrategy> strategyList) {
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
