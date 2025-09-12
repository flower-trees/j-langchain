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

package org.salt.jlangchain.rag.tools;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Slf4j
public class MCPManager {

    public static class ToolConfig {
        public String name;
        public String description;
        public String url;
        public String method;
        public List<String> params;
        public String authorization;
    }

    private final Map<String, ToolConfig> tools = new HashMap<>();
    private final OkHttpClient client;
    private final ObjectMapper mapper = new ObjectMapper();
    private final String configPath;
    private static final int DEFAULT_TIMEOUT_SECONDS = 30;

    public MCPManager(String configPath) throws Exception {
        this(configPath, DEFAULT_TIMEOUT_SECONDS);
    }

    public MCPManager(String configPath, int timeoutSeconds) throws Exception {
        this.configPath = configPath;
        this.client = new OkHttpClient.Builder()
                .connectTimeout(timeoutSeconds, TimeUnit.SECONDS)
                .readTimeout(timeoutSeconds, TimeUnit.SECONDS)
                .writeTimeout(timeoutSeconds, TimeUnit.SECONDS)
                .build();
        loadTools();
    }

    private void loadTools() throws Exception {
        ObjectMapper mapper = new ObjectMapper();

        File file = new File(configPath);
        InputStream is = null;
        try {
            if (file.exists()) {
                is = new FileInputStream(file);
            } else {
                is = Thread.currentThread().getContextClassLoader().getResourceAsStream(configPath);
                if (is == null) {
                    throw new IllegalArgumentException("Unable to find configuration file: " + configPath);
                }
            }

            Map<String, List<ToolConfig>> config = mapper.readValue(
                    is, new TypeReference<>() {}
            );

            if (config == null || config.get("tools") == null) {
                throw new IllegalArgumentException("Invalid configuration: missing 'tools' section");
            }

            tools.clear();
            for (ToolConfig t : config.get("tools")) {
                if (t.name == null || t.name.trim().isEmpty()) {
                    log.warn("Skipping tool with empty name");
                    continue;
                }
                if (t.url == null || t.url.trim().isEmpty()) {
                    log.warn("Skipping tool '{}' with empty URL", t.name);
                    continue;
                }
                if (t.method == null || t.method.trim().isEmpty()) {
                    log.warn("Skipping tool '{}' with empty method", t.name);
                    continue;
                }
                tools.put(t.name, t);
                log.info("loadTools: {} from {}", t.name, configPath);
            }

            if (tools.isEmpty()) {
                log.warn("No valid tools loaded from configuration: {}", configPath);
            }
        } finally {
            if (is != null) {
                try {
                    is.close();
                } catch (IOException e) {
                    log.error("Error closing configuration file stream", e);
                }
            }
        }
    }

    public List<ToolConfig> getAllTools() {
        return List.copyOf(tools.values());
    }

    /**
     * Execute a tool with given parameters
     * @param toolName the name of the tool to execute
     * @param input parameters for the tool as defined in config
     * @return execution result
     * @throws Exception if execution fails
     */
    public Object run(String toolName, Map<String, Object> input) throws Exception {
        return run(toolName, input, null);
    }

    /**
     * Execute a tool with given parameters and optional runtime authorization
     * @param toolName the name of the tool to execute
     * @param input parameters for the tool as defined in config
     * @param authorization runtime authorization header value (overrides config authorization)
     * @return execution result
     * @throws Exception if execution fails
     */
    public Object run(String toolName, Map<String, Object> input, String authorization) throws Exception {
        if (toolName == null || toolName.trim().isEmpty()) {
            throw new IllegalArgumentException("Tool name cannot be null or empty");
        }
        
        if (input == null) {
            input = new HashMap<>();
        }
        
        ToolConfig t = tools.get(toolName);
        if (t == null) {
            throw new IllegalArgumentException("Tool not found: " + toolName);
        }
        
        if (t.url == null || t.url.trim().isEmpty()) {
            throw new IllegalStateException("Tool URL is not configured for: " + toolName);
        }
        
        if (t.method == null || t.method.trim().isEmpty()) {
            throw new IllegalStateException("Tool method is not configured for: " + toolName);
        }

        if ("GET".equalsIgnoreCase(t.method)) {
            HttpUrl parsedUrl = HttpUrl.parse(t.url);
            if (parsedUrl == null) {
                throw new IllegalArgumentException("Invalid URL: " + t.url);
            }
            
            HttpUrl.Builder urlBuilder = parsedUrl.newBuilder();
            if (t.params != null) {
                for (String p : t.params) {
                    if (p != null && !p.trim().isEmpty()) {
                        Object value = input.get(p);
                        String paramValue = value != null ? String.valueOf(value) : "";
                        urlBuilder.addQueryParameter(p, paramValue);
                    }
                }
            }
            Request.Builder requestBuilder = new Request.Builder()
                    .url(urlBuilder.build())
                    .get();
            
            // Add authorization header - runtime auth takes precedence over config auth
            String authToUse = authorization != null ? authorization : t.authorization;
            if (authToUse != null && !authToUse.trim().isEmpty()) {
                requestBuilder.header("Authorization", authToUse);
            }
            
            Request request = requestBuilder.build();
            try (Response response = client.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    log.error("HTTP GET request failed with code: {} for tool: {}", response.code(), toolName);
                    throw new IOException("Unexpected response code: " + response.code());
                }
                
                ResponseBody responseBody = response.body();
                if (responseBody == null) {
                    log.warn("Empty response body for tool: {}", toolName);
                    return Map.of("result", "", "status", response.code());
                }
                
                String bodyString = responseBody.string();
                return Map.of("result", bodyString, "status", response.code());
            } catch (IOException e) {
                log.error("Error executing GET request for tool: {}", toolName, e);
                throw new RuntimeException("Failed to execute GET request: " + e.getMessage(), e);
            }
        } else if ("POST".equalsIgnoreCase(t.method) || "PUT".equalsIgnoreCase(t.method) || "PATCH".equalsIgnoreCase(t.method)) {
            Map<String, Object> bodyMap = new HashMap<>();
            if (t.params != null) {
                for (String p : t.params) {
                    if (p != null && !p.trim().isEmpty()) {
                        bodyMap.put(p, input.get(p));
                    }
                }
            }
            String json = mapper.writeValueAsString(bodyMap);
            RequestBody body = RequestBody.create(
                    json, MediaType.parse("application/json"));

            Request.Builder requestBuilder = new Request.Builder()
                    .url(t.url)
                    .header("Content-Type", "application/json");
            
            // Add authorization header - runtime auth takes precedence over config auth
            String authToUse = authorization != null ? authorization : t.authorization;
            if (authToUse != null && !authToUse.trim().isEmpty()) {
                requestBuilder.header("Authorization", authToUse);
            }
            
            if ("POST".equalsIgnoreCase(t.method)) {
                requestBuilder.post(body);
            } else if ("PUT".equalsIgnoreCase(t.method)) {
                requestBuilder.put(body);
            } else if ("PATCH".equalsIgnoreCase(t.method)) {
                requestBuilder.patch(body);
            }
            
            Request request = requestBuilder.build();
            
            try (Response response = client.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    log.error("HTTP {} request failed with code: {} for tool: {}", t.method, response.code(), toolName);
                    throw new IOException("Unexpected response code: " + response.code());
                }
                
                ResponseBody responseBody = response.body();
                String bodyString = responseBody != null ? responseBody.string() : "";
                return Map.of("result", bodyString, "status", response.code());
            } catch (IOException e) {
                log.error("Error executing {} request for tool: {}", t.method, toolName, e);
                throw new RuntimeException("Failed to execute " + t.method + " request: " + e.getMessage(), e);
            }
        } else {
            throw new UnsupportedOperationException("Unsupported HTTP method: " + t.method);
        }
    }
}