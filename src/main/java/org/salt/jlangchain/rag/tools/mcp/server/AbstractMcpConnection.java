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

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.salt.jlangchain.rag.tools.mcp.server.config.ServerConfig;
import org.salt.jlangchain.rag.tools.mcp.tool.ToolDesc;
import org.salt.jlangchain.rag.tools.mcp.tool.ToolResult;
import org.salt.jlangchain.rag.tools.mcp.tool.ToolsListResponse;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Abstract base class for MCP connections
 * Provides common implementation logic
 */
@Slf4j
public abstract class AbstractMcpConnection implements McpConnection {

    @Getter
    protected final String serverName;
    protected final ServerConfig config;
    protected final ObjectMapper mapper = new ObjectMapper();

    protected int requestId = 1;
    protected volatile boolean connected = false;

    @Getter
    protected String lastError;

    public AbstractMcpConnection(String serverName, ServerConfig config) {
        this.serverName = serverName;
        this.config = config;
    }

    /**
     * Perform initialization handshake
     * All implementation classes have the same handshake process
     */
    protected void performHandshake() throws Exception {
        // Step 1 & 2: Send initialize request and wait for response
        Map<String, Object> initParams = Map.of(
                "protocolVersion", "2024-11-05",
                "capabilities", Map.of(),
                "clientInfo", Map.of(
                        "name", "multi-mcp-java-client",
                        "version", "1.0.0"
                )
        );

        McpResponse initResponse = sendRequest("initialize", initParams);
        log.info("[{}] Initialize response: {}", serverName, mapper.writeValueAsString(initResponse));

        // Step 3: Send initialized notification
        sendNotification("notifications/initialized", new HashMap<>());

        log.info("[{}] Handshake completed", serverName);
    }

    /**
     * Send notification (no response required)
     * Subclasses need to implement the specific sending logic
     */
    protected abstract void sendNotification(String method, Object params) throws Exception;

    @Override
    public List<ToolDesc> listTools() throws Exception {
        McpResponse response = sendRequest("tools/list", new HashMap<>());
        ToolsListResponse toolsResponse = mapper.convertValue(response.result, ToolsListResponse.class);
        return toolsResponse.tools;
    }

    @Override
    public ToolResult callTool(String toolName, Map<String, Object> arguments) throws Exception {
        Map<String, Object> params = Map.of(
                "name", toolName,
                "arguments", arguments != null ? arguments : new HashMap<>()
        );

        McpResponse response = sendRequest("tools/call", params);
        return mapper.convertValue(response.result, ToolResult.class);
    }

    @Override
    public boolean isConnected() {
        return connected;
    }

    /**
     * Generate the next request ID
     */
    protected synchronized int nextRequestId() {
        return requestId++;
    }
}