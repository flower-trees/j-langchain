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
import java.io.InputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Slf4j
public class MCPManager {

    public static class ToolConfig {
        public String name;
        public String description;
        public String url;
        public String method;
        public List<String> params;
    }

    private final Map<String, ToolConfig> tools = new HashMap<>();
    private final OkHttpClient client = new OkHttpClient();
    private final ObjectMapper mapper = new ObjectMapper();
    private final String configPath;

    public MCPManager(String configPath) throws Exception {
        this.configPath = configPath;
        loadTools();
    }

    private void loadTools() throws Exception {
        ObjectMapper mapper = new ObjectMapper();

        File file = new File(configPath);
        InputStream is;
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

        tools.clear();
        for (ToolConfig t : config.get("tools")) {
            tools.put(t.name, t);
            log.info("loadTools: {} from {}", t.name, configPath);
        }
    }

    public List<ToolConfig> getAllTools() {
        return List.copyOf(tools.values());
    }

    public Object run(String toolName, Map<String, Object> input) throws Exception {
        ToolConfig t = tools.get(toolName);
        if (t == null) throw new RuntimeException("Tool not found");

        if ("GET".equalsIgnoreCase(t.method)) {
            HttpUrl.Builder urlBuilder = Objects.requireNonNull(HttpUrl.parse(t.url)).newBuilder();
            for (String p : t.params) {
                urlBuilder.addQueryParameter(p, String.valueOf(input.getOrDefault(p, "")));
            }
            Request request = new Request.Builder()
                    .url(urlBuilder.build())
                    .get()
                    .build();
            try (Response response = client.newCall(request).execute()) {
                assert response.body() != null;
                return Map.of("result", response.body().string());
            }
        } else {
            Map<String, Object> bodyMap = new HashMap<>();
            for (String p : t.params) {
                bodyMap.put(p, input.get(p));
            }
            String json = mapper.writeValueAsString(bodyMap);
            RequestBody body = RequestBody.create(
                    json, MediaType.parse("application/json"));

            Request request = new Request.Builder()
                    .url(t.url)
                    .post(body)
                    .build();
            try (Response response = client.newCall(request).execute()) {
                return Map.of("result", response.body() != null ? response.body().string() : "");
            }
        }
    }
}