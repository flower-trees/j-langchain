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

package org.salt.jlangchain.rag.tools.mcp.server;

import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import org.salt.jlangchain.rag.tools.mcp.server.config.ServerConfig;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Slf4j
public class McpHttpConnection extends AbstractMcpConnection {

    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    private final String baseUrl;
    private final OkHttpClient httpClient;
    private String sessionId;

    public McpHttpConnection(String serverName, ServerConfig config) {
        super(serverName, config);
        this.baseUrl = config.url;

        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(10, TimeUnit.SECONDS)
                .addInterceptor(chain -> {
                    Request request = chain.request();
                    log.debug("[{}] HTTP Request: {} {}", serverName, request.method(), request.url());

                    Response response = chain.proceed(request);
                    log.debug("[{}] HTTP Response: {} {}", serverName, response.code(), response.message());

                    // 从响应头中获取 MCP session ID
                    String mcpSessionId = response.header("mcp-session-id");
                    if (mcpSessionId != null) {
                        if (sessionId == null) {
                            sessionId = mcpSessionId;
                            log.info("[{}] Received MCP session ID: {}", serverName, sessionId);
                        } else if (!sessionId.equals(mcpSessionId)) {
                            log.warn("[{}] Session ID changed from {} to {}",
                                    serverName, sessionId, mcpSessionId);
                            sessionId = mcpSessionId;
                        }
                    }

                    return response;
                })
                .build();
    }

    @Override
    public ConnectionType getConnectionType() {
        return ConnectionType.HTTP;
    }

    @Override
    public void connect() throws IOException {
        try {
            connected = true;
            performHandshake();
            log.info("[{}] HTTP connection established with session: {}", serverName, sessionId);
        } catch (Exception e) {
            connected = false;
            throw new IOException("Failed to connect to HTTP server", e);
        }
    }

    @Override
    public synchronized McpResponse sendRequest(String method, Object params) throws Exception {
        if (!connected) {
            throw new IllegalStateException("Not connected to server: " + serverName);
        }

        McpRequest request = new McpRequest();
        request.jsonrpc = "2.0";
        request.id = nextRequestId();
        request.method = method;
        request.params = params;

        String requestJson = mapper.writeValueAsString(request);
        log.debug("[{}] Sending request: {}", serverName, requestJson);

        String responseText = sendHttpRequest(requestJson);

        // 解析 SSE 格式的响应
        String responseJson = parseSseResponse(responseText);
        log.debug("[{}] Parsed JSON response: {}", serverName, responseJson);

        McpResponse response = mapper.readValue(responseJson, McpResponse.class);

        if (response.error != null) {
            throw new McpException(response.error.code, response.error.message, response.error.data);
        }

        return response;
    }

    @Override
    protected void sendNotification(String method, Object params) throws Exception {
        Map<String, Object> notification = new HashMap<>();
        notification.put("jsonrpc", "2.0");
        notification.put("method", method);
        notification.put("params", params);

        String notificationJson = mapper.writeValueAsString(notification);
        log.debug("[{}] Sending notification: {}", serverName, notificationJson);

        sendHttpRequest(notificationJson);
    }

    /**
     * 解析 SSE 格式的响应
     *
     * SSE 格式示例:
     * event: message
     * data: {"jsonrpc":"2.0","id":1,"result":{...}}
     *
     * @param sseText SSE 格式文本
     * @return 提取的 JSON 字符串
     */
    private String parseSseResponse(String sseText) throws IOException {
        if (sseText == null || sseText.trim().isEmpty()) {
            return "{}";
        }

        // 检查是否是 SSE 格式
        if (!sseText.contains("data:")) {
            // 如果不是 SSE 格式，直接返回（可能是纯 JSON）
            return sseText.trim();
        }

        StringBuilder jsonData = new StringBuilder();

        try (BufferedReader reader = new BufferedReader(new StringReader(sseText))) {
            String line;
            boolean isDataLine = false;

            while ((line = reader.readLine()) != null) {
                line = line.trim();

                if (line.isEmpty()) {
                    // 空行表示一个事件结束
                    continue;
                }

                if (line.startsWith("event:")) {
                    // 事件类型行
                    String eventType = line.substring(6).trim();
                    log.debug("[{}] SSE event type: {}", serverName, eventType);
                    isDataLine = false;
                } else if (line.startsWith("data:")) {
                    // 数据行
                    String data = line.substring(5).trim();
                    if (jsonData.length() > 0) {
                        jsonData.append("\n");
                    }
                    jsonData.append(data);
                    isDataLine = true;
                } else if (line.startsWith(":")) {
                    // 注释行，忽略
                    continue;
                } else if (isDataLine) {
                    // 多行数据的延续
                    jsonData.append("\n").append(line);
                } else if (line.startsWith("id:") || line.startsWith("retry:")) {
                    // SSE 的其他字段，忽略
                    continue;
                }
            }
        }

        String result = jsonData.toString().trim();
        if (result.isEmpty()) {
            throw new IOException("No data found in SSE response: " + sseText);
        }

        return result;
    }

    private String sendHttpRequest(String jsonBody) throws IOException {
        RequestBody body = RequestBody.create(jsonBody, JSON);
        Request.Builder requestBuilder = new Request.Builder()
                .url(baseUrl)
                .post(body)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream");

        if (sessionId != null) {
            requestBuilder.header("mcp-session-id", sessionId);
        }

        Request request = requestBuilder.build();

        try (Response response = httpClient.newCall(request).execute()) {
            String contentType = response.header("Content-Type");
            log.debug("[{}] Response code: {}, Content-Type: {}",
                    serverName, response.code(), contentType);

            if (!response.isSuccessful()) {
                String errorBody = "";
                if (response.body() != null) {
                    errorBody = response.body().string();
                    log.error("[{}] Error response body: {}", serverName, errorBody);
                }

                lastError = String.format("HTTP %d: %s", response.code(), errorBody);

                throw new IOException(String.format(
                        "HTTP request to %s failed with code %d. Body: %s",
                        baseUrl, response.code(), errorBody
                ));
            }

            if (response.body() != null) {
                String responseBody = response.body().string();
                log.debug("[{}] Raw response body: {}", serverName, responseBody);
                return responseBody;
            } else {
                return "{}";
            }
        }
    }

    @Override
    public void close() {
        if (connected && sessionId != null) {
            try {
                Request request = new Request.Builder()
                        .url(baseUrl)
                        .delete()
                        .header("mcp-session-id", sessionId)
                        .header("Accept", "application/json, text/event-stream")
                        .build();

                try (Response response = httpClient.newCall(request).execute()) {
                    log.info("[{}] Session cleanup: {}", serverName, response.code());
                }
            } catch (Exception e) {
                log.warn("[{}] Failed to cleanup session: {}", serverName, e.getMessage());
            }
        }

        connected = false;
        sessionId = null;
        log.info("[{}] HTTP connection closed", serverName);
    }
}