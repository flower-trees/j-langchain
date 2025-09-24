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

package org.salt.jlangchain.rag.tools.npx.server;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.salt.jlangchain.rag.tools.npx.tool.Tool;
import org.salt.jlangchain.rag.tools.npx.tool.ToolResult;
import org.salt.jlangchain.rag.tools.npx.tool.ToolsListResponse;
import org.salt.jlangchain.rag.tools.npx.config.ServerConfig;

import java.io.*;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Slf4j
public class McpServerConnection {

    @Getter
    private final String serverName;
    private final ServerConfig config;
    private final ObjectMapper mapper = new ObjectMapper();
    private Process serverProcess;
    private BufferedWriter stdin;
    private BufferedReader stdout;
    private BufferedReader stderr;
    private int requestId = 1;
    private volatile boolean connected = false;
    @Getter
    private String lastError;

    public McpServerConnection(String serverName, ServerConfig config) {
        this.serverName = serverName;
        this.config = config;
    }

    public void connect() throws IOException {
        List<String> command = new ArrayList<>();
        command.add(config.command);
        command.addAll(config.args);

        ProcessBuilder pb = new ProcessBuilder(command);

        if (config.env != null) {
            pb.environment().putAll(config.env);
        }

        serverProcess = pb.start();
        stdin = new BufferedWriter(new OutputStreamWriter(serverProcess.getOutputStream()));
        stdout = new BufferedReader(new InputStreamReader(serverProcess.getInputStream()));
        stderr = new BufferedReader(new InputStreamReader(serverProcess.getErrorStream()));

        connected = true;

        startErrorListener();
        performHandshake();
    }

    private void performHandshake() throws IOException {
        try {
            Map<String, Object> initParams = Map.of(
                "protocolVersion", "2024-11-05",
                "capabilities", Map.of(),
                "clientInfo", Map.of(
                    "name", "multi-mcp-java-client",
                    "version", "1.0.0"
                )
            );

            sendRequest("initialize", initParams);
//            sendRequest("initialized", new HashMap<>());
        } catch (Exception e) {
            throw new IOException("Handshake failed", e);
        }
    }

    private void startErrorListener() {
        Thread errorThread = new Thread(() -> {
            try {
                String line;
                while ((line = stderr.readLine()) != null) {
                    if (line.isEmpty() || line.contains("error")) {
                        log.error("[{} ERROR]: {}", serverName, line);
                        lastError = line;
                    } else {
                        log.info("[{} INFO]: {}", serverName, line);
                    }
                }
            } catch (IOException e) {
                log.error("error start server {}: {}", serverName, e.getMessage());
                lastError = "error start server: " + serverName + ", " +e.getMessage();
            }
        });
        errorThread.setDaemon(true);
        errorThread.start();
    }

    public synchronized McpResponse sendRequest(String method, Object params) throws Exception {
        if (!connected) {
            throw new IllegalStateException("Not connected to server: " + serverName);
        }

        McpRequest request = new McpRequest();
        request.jsonrpc = "2.0";
        request.id = requestId++;
        request.method = method;
        request.params = params;

        String requestJson = mapper.writeValueAsString(request);
        stdin.write(requestJson + "\n");
        stdin.flush();

        String responseJson = stdout.readLine();
        if (responseJson == null) {
            throw new IOException("Server closed connection: " + serverName);
        }

        McpResponse response = mapper.readValue(responseJson, McpResponse.class);

        if (response.error != null) {
            throw new McpException(response.error.code, response.error.message, response.error.data);
        }

        return response;
    }

    public List<Tool> listTools() throws Exception {
        McpResponse response = sendRequest("tools/list", new HashMap<>());
        ToolsListResponse toolsResponse = mapper.convertValue(response.result, ToolsListResponse.class);
        return toolsResponse.tools;
    }

    public ToolResult callTool(String toolName, Map<String, Object> arguments) throws Exception {
        Map<String, Object> params = Map.of(
            "name", toolName,
            "arguments", arguments != null ? arguments : new HashMap<>()
        );

        McpResponse response = sendRequest("tools/call", params);
        return mapper.convertValue(response.result, ToolResult.class);
    }

    public boolean isConnected() {
        return connected && serverProcess != null && serverProcess.isAlive();
    }

    public void close() {
        connected = false;
        try {
            if (stdin != null) stdin.close();
            if (stdout != null) stdout.close();
            if (stderr != null) stderr.close();
            if (serverProcess != null) {
                serverProcess.destroy();
                if (!serverProcess.waitFor(5, TimeUnit.SECONDS)) {
                    serverProcess.destroyForcibly();
                }
            }
        } catch (Exception e) {
            log.error("Error close server {}: {}", serverName, e.getMessage());
        }
    }
}