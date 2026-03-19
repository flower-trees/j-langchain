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

import org.salt.jlangchain.rag.tools.mcp.server.param.McpResponse;
import org.salt.jlangchain.rag.tools.mcp.tool.ToolDesc;
import org.salt.jlangchain.rag.tools.mcp.tool.ToolResult;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * MCP (Model Context Protocol) connection interface
 * Defines unified methods for interacting with MCP servers
 */
public interface McpConnection {

    /**
     * Connect to MCP server and complete initialization handshake
     *
     * @throws IOException thrown when connection fails
     */
    void connect() throws IOException;

    /**
     * Send request to MCP server and wait for response
     *
     * @param method method name
     * @param params parameter object
     * @return MCP response
     * @throws Exception thrown when request fails
     */
    McpResponse sendRequest(String method, Object params) throws Exception;

    /**
     * List all tools provided by the server
     *
     * @return list of tool descriptions
     * @throws Exception thrown when request fails
     */
    List<ToolDesc> listTools() throws Exception;

    /**
     * Call the specified tool
     *
     * @param toolName tool name
     * @param arguments tool arguments
     * @return tool execution result
     * @throws Exception thrown when call fails
     */
    ToolResult callTool(String toolName, Map<String, Object> arguments) throws Exception;

    /**
     * Check if connection is active
     *
     * @return true if connection is normal, otherwise false
     */
    boolean isConnected();

    /**
     * Close connection and release resources
     */
    void close();

    /**
     * Get server name
     *
     * @return server name
     */
    String getServerName();

    /**
     * Get last error message
     *
     * @return error message, or null if no error
     */
    String getLastError();

    /**
     * Get connection type
     *
     * @return connection type (STDIO, SSE, HTTP)
     */
    ConnectionType getConnectionType();

    /**
     * Connection type enumeration
     */
    enum ConnectionType {
        STDIO,  // Standard input/output (inter-process communication)
        SSE,    // Server-Sent Events (server push events)
        HTTP    // HTTP request/response
    }
}