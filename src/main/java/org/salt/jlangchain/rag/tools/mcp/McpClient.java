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

package org.salt.jlangchain.rag.tools.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.salt.jlangchain.rag.tools.mcp.server.config.McpConfig;
import org.salt.jlangchain.rag.tools.mcp.server.McpServerConnection;
import org.salt.jlangchain.rag.tools.mcp.server.ServerStatus;
import org.salt.jlangchain.rag.tools.mcp.tool.ToolDesc;
import org.salt.jlangchain.rag.tools.mcp.tool.ToolResult;
import org.springframework.beans.factory.DisposableBean;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Slf4j
public class McpClient implements DisposableBean {

    private final Map<String, McpServerConnection> servers = new ConcurrentHashMap<>();

    public McpClient() {
        this(null);
    }

    public McpClient(String configPath) {
        try {
            McpConfig config;
            if (configPath == null) {
                config = loadDefaultConfig();
            } else {
                config = loadConfig(configPath);
            }
            initializeFromConfig(config);
        } catch (IOException e) {
            log.error("Failed to load default config: {}", e.getMessage());
        }
    }

    public void initializeFromConfig(McpConfig config) {
        config.mcpServers.forEach((serverName, serverConfig) -> {
            try {
                McpServerConnection connection = new McpServerConnection(serverName, serverConfig);
                connection.connect();
                servers.put(serverName, connection);
                log.info("Connected to MCP server: {}", serverName);
            } catch (Exception e) {
                log.error("Failed to connect to {}: {}", serverName, e.getMessage());
            }
        });
    }

    public McpConfig loadDefaultConfig() throws IOException {
        return loadConfig("mcp.server.config.json");
    }

    public McpConfig loadConfig(String configPath) throws IOException {

        ObjectMapper mapper = new ObjectMapper();

        File configFile = new File(configPath);
        if (configFile.exists()) {
            McpConfig config = mapper.readValue(configFile, McpConfig.class);
            return processEnvironmentVariables(config);
        }

        InputStream configStream = getClass().getClassLoader().getResourceAsStream(configPath);
        if (configStream != null) {
            McpConfig config = mapper.readValue(configStream, McpConfig.class);
            return processEnvironmentVariables(config);
        }

        throw new FileNotFoundException("Configuration file 'config.json' not found in current directory or classpath");
    }

    public McpConfig processEnvironmentVariables(McpConfig config) {
        ObjectMapper mapper = new ObjectMapper();

        try {
            String configJson = mapper.writeValueAsString(config);
            configJson = replaceEnvironmentVariables(configJson);
            return mapper.readValue(configJson, McpConfig.class);
        } catch (Exception e) {
            System.err.println("Warning: Failed to process environment variables: " + e.getMessage());
            return config;
        }
    }

    private String replaceEnvironmentVariables(String input) {
        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile("\\$\\{([^}:]+)(?::([^}]*))?\\}");
        java.util.regex.Matcher matcher = pattern.matcher(input);

        StringBuilder result = new StringBuilder();

        while (matcher.find()) {
            String varName = matcher.group(1);
            String defaultValue = matcher.group(2);

            String envValue = System.getenv(varName);
            if (envValue == null) {
                envValue = System.getProperty(varName);
            }

            String replacement;
            if (envValue != null) {
                replacement = envValue;
            } else if (defaultValue != null) {
                replacement = defaultValue;
            } else {
                System.err.println("Warning: Environment variable '" + varName + "' not found");
                replacement = matcher.group(0);
            }

            matcher.appendReplacement(result, java.util.regex.Matcher.quoteReplacement(replacement));
        }

        matcher.appendTail(result);
        return result.toString();
    }

    public Map<String, List<ToolDesc>> listAllTools() {
        return servers.entrySet()
            .stream()
            .collect(Collectors.toMap(
                Map.Entry::getKey,
                entry -> {
                    String serverName = entry.getKey();
                    McpServerConnection connection = entry.getValue();
                    try {
                        return connection.listTools();
                    } catch (Exception e) {
                        log.error("Failed to list tools for {}: {}", serverName, e.getMessage());
                        return Collections.<ToolDesc>emptyList();
                    }
                }
            ));
    }

    public ToolResult callTool(String serverName, String toolName, Map<String, Object> arguments) {
        McpServerConnection connection = servers.get(serverName);
        if (connection == null) {
            throw new IllegalArgumentException("Server not found: " + serverName);
        }

        try {
            return connection.callTool(toolName, arguments);
        } catch (Exception e) {
            throw new RuntimeException("Tool call failed", e);
        }
    }

    public Map<String, ServerStatus> getServerStatuses() {
        return servers.entrySet().stream()
            .collect(Collectors.toMap(
                Map.Entry::getKey,
                entry -> {
                    McpServerConnection conn = entry.getValue();
                    return new ServerStatus(
                        conn.isConnected(),
                        conn.getServerName(),
                        conn.getLastError()
                    );
                }
            ));
    }

    @Override
    public void destroy() {
        log.info("Shutting down NPXMcpClient...");
        try {
            servers.values().forEach(connection -> {
                try {
                    connection.close();
                } catch (Exception e) {
                    log.warn("Error closing connection {}: {}", connection.getServerName(), e.getMessage());
                }
            });
            servers.clear();
            log.info("NPXMcpClient shutdown completed");
        } catch (Exception e) {
            log.error("Error during NPXMcpClient shutdown: {}", e.getMessage(), e);
            throw e;
        }
    }
}