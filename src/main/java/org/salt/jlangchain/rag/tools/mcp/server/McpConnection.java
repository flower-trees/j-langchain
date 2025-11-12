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

import org.salt.jlangchain.rag.tools.mcp.tool.ToolDesc;
import org.salt.jlangchain.rag.tools.mcp.tool.ToolResult;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * MCP (Model Context Protocol) 连接接口
 * 定义了与 MCP 服务器交互的统一方法
 */
public interface McpConnection {

    /**
     * 连接到 MCP 服务器并完成初始化握手
     *
     * @throws IOException 连接失败时抛出
     */
    void connect() throws IOException;

    /**
     * 发送请求到 MCP 服务器并等待响应
     *
     * @param method 方法名
     * @param params 参数对象
     * @return MCP 响应
     * @throws Exception 请求失败时抛出
     */
    McpResponse sendRequest(String method, Object params) throws Exception;

    /**
     * 列出服务器提供的所有工具
     *
     * @return 工具描述列表
     * @throws Exception 请求失败时抛出
     */
    List<ToolDesc> listTools() throws Exception;

    /**
     * 调用指定的工具
     *
     * @param toolName 工具名称
     * @param arguments 工具参数
     * @return 工具执行结果
     * @throws Exception 调用失败时抛出
     */
    ToolResult callTool(String toolName, Map<String, Object> arguments) throws Exception;

    /**
     * 检查连接是否活跃
     *
     * @return true 如果连接正常，否则 false
     */
    boolean isConnected();

    /**
     * 关闭连接并释放资源
     */
    void close();

    /**
     * 获取服务器名称
     *
     * @return 服务器名称
     */
    String getServerName();

    /**
     * 获取最后一次错误信息
     *
     * @return 错误信息，如果没有错误则返回 null
     */
    String getLastError();

    /**
     * 获取连接类型
     *
     * @return 连接类型（STDIO, SSE, HTTP）
     */
    ConnectionType getConnectionType();

    /**
     * 连接类型枚举
     */
    enum ConnectionType {
        STDIO,  // 标准输入输出（进程间通信）
        SSE,    // Server-Sent Events（服务器推送事件）
        HTTP    // HTTP 请求/响应
    }
}