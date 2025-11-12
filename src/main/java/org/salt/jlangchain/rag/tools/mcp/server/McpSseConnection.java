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
import okhttp3.sse.EventSource;
import okhttp3.sse.EventSourceListener;
import okhttp3.sse.EventSources;
import org.jetbrains.annotations.NotNull;
import org.salt.jlangchain.rag.tools.mcp.server.config.ServerConfig;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.*;

@Slf4j
public class McpSseConnection extends AbstractMcpConnection {

    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    private final String sseUrl;
    private final OkHttpClient httpClient;
    private EventSource eventSource;
    private String messageEndpoint;  // Dynamically obtained message endpoint
    private final CountDownLatch endpointLatch = new CountDownLatch(1);

    private final Map<Integer, CompletableFuture<McpResponse>> pendingResponses = new ConcurrentHashMap<>();

    public McpSseConnection(String serverName, ServerConfig config) {
        super(serverName, config);
        this.sseUrl = config.url;

        // Create OkHttp client
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(0, TimeUnit.SECONDS)  // SSE requires long connection
                .writeTimeout(10, TimeUnit.SECONDS)
                .build();
    }

    @Override
    public ConnectionType getConnectionType() {
        return ConnectionType.SSE;
    }

    @Override
    public void connect() throws IOException {
        try {
            startSseConnection();

            // Wait for endpoint event
            boolean received = endpointLatch.await(10, TimeUnit.SECONDS);
            if (!received) {
                throw new IOException("Timeout waiting for endpoint event from SSE server");
            }

            if (messageEndpoint == null) {
                throw new IOException("Did not receive message endpoint from SSE server");
            }

            connected = true;

            log.info("[{}] SSE connection established, message endpoint: {}", serverName, messageEndpoint);

            // Perform handshake
            performHandshake();

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while connecting to SSE server", e);
        } catch (Exception e) {
            throw new IOException("Failed to connect to SSE server", e);
        }
    }

    private void startSseConnection() {
        Request request = new Request.Builder()
                .url(sseUrl)
                .header("Accept", "text/event-stream")
                .header("Cache-Control", "no-cache")
                .build();

        EventSourceListener listener = new EventSourceListener() {
            @Override
            public void onOpen(@NotNull EventSource eventSource, @NotNull Response response) {
                log.info("[{}] SSE connection opened", serverName);
            }

            @Override
            public void onEvent(@NotNull EventSource eventSource, String id, String type, @NotNull String data) {
                log.debug("[{}] SSE event - type: {}, data: {}", serverName, type, data);

                if ("endpoint".equals(type)) {
                    // Received endpoint event, extract message endpoint
                    handleEndpointEvent(data);
                } else if ("message".equals(type) || type == null) {
                    // Received message response
                    processMessage(data);
                } else {
                    log.debug("[{}] Received unknown event type: {}", serverName, type);
                }
            }

            @Override
            public void onClosed(@NotNull EventSource eventSource) {
                log.info("[{}] SSE connection onClosed", serverName);
                if (connected) {
                    connected = false;
                    lastError = "SSE connection closed by server";
                }
            }

            @Override
            public void onFailure(@NotNull EventSource eventSource, Throwable t, Response response) {
                log.error("[{}] SSE connection failed: ", serverName, t);
                if (connected) {
                    lastError = "SSE connection failed: " + (t != null ? t.getMessage() : "");
                }
                connected = false;
            }
        };

        eventSource = EventSources.createFactory(httpClient)
                .newEventSource(request, listener);
    }

    private void handleEndpointEvent(String data) {
        try {
            // data format: /messages/?session_id=276347cd41ae4b7686b4d97b9112980b
            // Build complete message endpoint URL
            String baseUrl = sseUrl.substring(0, sseUrl.lastIndexOf("/sse"));
            messageEndpoint = baseUrl + data;

            log.info("[{}] Received message endpoint: {}", serverName, messageEndpoint);
            endpointLatch.countDown();
        } catch (Exception e) {
            log.error("[{}] Failed to parse endpoint event: {}", serverName, data, e);
            lastError = "Failed to parse endpoint: " + e.getMessage();
        }
    }

    private void processMessage(String message) {
        try {
            log.debug("[{}] Processing SSE message: {}", serverName, message);

            McpResponse response = mapper.readValue(message, McpResponse.class);

            if (response.id != null) {
                CompletableFuture<McpResponse> future = pendingResponses.remove(response.id);
                if (future != null) {
                    if (response.error != null) {
                        future.completeExceptionally(
                                new McpException(response.error.code, response.error.message, response.error.data)
                        );
                    } else {
                        future.complete(response);
                    }
                } else {
                    log.warn("[{}] Received response for unknown request id: {}", serverName, response.id);
                }
            } else {
                // Notification sent by server proactively
                log.info("[{}] Received server notification: {}", serverName, message);
            }
        } catch (Exception e) {
            log.error("[{}] Failed to process SSE message: {}, error: {}",
                    serverName, message, e.getMessage(), e);
        }
    }

    @Override
    public synchronized McpResponse sendRequest(String method, Object params) throws Exception {
        if (!connected) {
            throw new IllegalStateException("Not connected to server: " + serverName);
        }

        if (messageEndpoint == null) {
            throw new IllegalStateException("Message endpoint not available");
        }

        int currentRequestId = nextRequestId();

        McpRequest request = new McpRequest();
        request.jsonrpc = "2.0";
        request.id = currentRequestId;
        request.method = method;
        request.params = params;

        CompletableFuture<McpResponse> responseFuture = new CompletableFuture<>();
        pendingResponses.put(currentRequestId, responseFuture);

        try {
            String requestJson = mapper.writeValueAsString(request);
            log.debug("[{}] Sending request: {}", serverName, requestJson);

            sendHttpPost(requestJson);

            return responseFuture.get(30, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            pendingResponses.remove(currentRequestId);
            throw new IOException("Request timeout for method: " + method);
        } catch (Exception e) {
            pendingResponses.remove(currentRequestId);
            throw e;
        }
    }

    @Override
    protected void sendNotification(String method, Object params) throws Exception {
        if (!connected || messageEndpoint == null) {
            throw new IllegalStateException("Not connected to server or endpoint not available");
        }

        Map<String, Object> notification = new HashMap<>();
        notification.put("jsonrpc", "2.0");
        notification.put("method", method);
        notification.put("params", params);

        String notificationJson = mapper.writeValueAsString(notification);
        log.debug("[{}] Sending notification: {}", serverName, notificationJson);

        sendHttpPost(notificationJson);
    }

    private void sendHttpPost(String jsonBody) throws IOException {
        RequestBody body = RequestBody.create(jsonBody, JSON);
        Request request = new Request.Builder()
                .url(messageEndpoint)
                .post(body)
                .build();

        try (Response response = httpClient.newCall(request).execute()) {
            log.debug("[{}] HTTP POST response code: {}", serverName, response.code());

            if (!response.isSuccessful() && response.code() != 204) {
                String errorBody = response.body() != null ? response.body().string() : "";
                throw new IOException(String.format(
                        "HTTP POST to %s failed with code %d. Body: %s",
                        messageEndpoint, response.code(), errorBody
                ));
            }
        }
    }

    @Override
    public boolean isConnected() {
        return connected && eventSource != null;
    }

    @Override
    public void close() {
        connected = false;

        // Cancel all pending requests
        pendingResponses.values().forEach(future ->
                future.completeExceptionally(new IOException("Connection closed"))
        );
        pendingResponses.clear();

        // Close SSE connection
        if (eventSource != null) {
            eventSource.cancel();
            eventSource = null;
        }

        log.info("[{}] SSE connection closed", serverName);
    }
}