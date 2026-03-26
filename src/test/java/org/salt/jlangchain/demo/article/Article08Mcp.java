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

package org.salt.jlangchain.demo.article;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.salt.jlangchain.TestApplication;
import org.salt.jlangchain.config.JLangchainConfigTest;
import org.salt.jlangchain.rag.tools.mcp.McpClient;
import org.salt.jlangchain.rag.tools.mcp.McpManager;
import org.salt.jlangchain.rag.tools.mcp.server.McpServerConnection;
import org.salt.jlangchain.rag.tools.mcp.server.config.ServerConfig;
import org.salt.jlangchain.utils.JsonUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.junit4.SpringRunner;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 文章8：在 Java AI 应用中集成 MCP 工具协议
 *
 * MCP（Model Context Protocol）是 Anthropic 主导的 AI 工具标准协议（2024年发布）。
 *
 * 演示内容：
 * 1. mcpManagerManifest  - 列出所有已注册的 MCP 工具
 * 2. mcpManagerRun       - 通过 McpManager 调用工具
 * 3. mcpClientListTools  - 通过 McpClient 列出 NPX MCP 服务器的工具
 * 4. mcpServerConnect    - 连接 MCP 服务器并调用工具（Memory Server）
 * 5. mcpPostgresConnect  - 连接 PostgreSQL MCP 服务器
 *
 * 配置文件：
 * - mcp.config.json          : HTTP API 工具配置
 * - mcp.server.config.json   : NPX MCP 服务器配置
 */
@RunWith(SpringRunner.class)
@SpringBootTest(classes = {TestApplication.class, JLangchainConfigTest.class})
@SpringBootConfiguration
public class Article08Mcp {

    @Autowired
    private McpManager mcpManager;

    @Autowired
    private McpClient mcpClient;

    /**
     * 查看所有已注册的 MCP 工具清单
     * McpManager 从 mcp.config.json 加载 HTTP API 工具
     */
    @Test
    public void mcpManagerManifest() {
        System.out.println("=== MCP 工具清单 ===");
        System.out.println(JsonUtil.toJson(mcpManager.manifest()));
        System.out.println();
        System.out.println("=== MCP 工具（带输入格式）===");
        System.out.println(JsonUtil.toJson(mcpManager.manifestForInput()));
    }

    /**
     * 通过 McpManager 调用 HTTP API 工具
     * 工具定义来自 mcp.config.json
     *
     * mcp.config.json 示例：
     * {
     *   "default": [
     *     {
     *       "name": "get_export_ip",
     *       "description": "Get my network export IP",
     *       "url": "http://ipinfo.io/ip",
     *       "method": "GET"
     *     }
     *   ]
     * }
     */
    @Test
    public void mcpManagerRun() throws Exception {
        System.out.println("=== 调用 HTTP API 工具：get_export_ip ===");

        // run：直接调用并返回结果
        Object result = mcpManager.run("default", "get_export_ip", Map.of());
        System.out.println("结果：" + JsonUtil.toJson(result));

        // runForInput：返回 LLM 格式化输入（用于注入 Agent Prompt）
        Object inputResult = mcpManager.runForInput("default", "get_export_ip", Map.of());
        System.out.println("LLM 格式化输入：" + JsonUtil.toJson(inputResult));
    }

    /**
     * 通过 McpClient 列出 NPX MCP 服务器上的所有工具
     * McpClient 从 mcp.server.config.json 加载 NPX MCP 服务器配置
     *
     * mcp.server.config.json 示例：
     * {
     *   "mcpServers": {
     *     "filesystem": {
     *       "command": "npx",
     *       "args": ["-y", "@modelcontextprotocol/server-filesystem", "/tmp"]
     *     },
     *     "memory": {
     *       "command": "npx",
     *       "args": ["-y", "@modelcontextprotocol/server-memory"]
     *     }
     *   }
     * }
     */
    @Test
    public void mcpClientListTools() {
        System.out.println("=== NPX MCP 服务器工具列表 ===");
        System.out.println(JsonUtil.toJson(mcpClient.listAllTools()));
    }

    /**
     * 直接连接 Memory MCP 服务器并调用工具
     * Memory Server 提供持久化键值存储，适合 Agent 记忆场景
     */
    @Test
    public void mcpMemoryServerConnect() throws Exception {
        // 配置 Memory MCP 服务器
        ServerConfig config = new ServerConfig();
        config.command = "npx";
        config.args = List.of("-y", "@modelcontextprotocol/server-memory");
        config.env = new HashMap<>();

        McpServerConnection connection = new McpServerConnection("memory-server", config);
        connection.connect();

        System.out.println("=== Memory MCP 服务器 ===");
        System.out.println("服务器名称：" + connection.getServerName());
        System.out.println("连接状态：" + connection.isConnected());
        System.out.println("工具列表：" + JsonUtil.toJson(connection.listTools()));

        // 调用工具
        System.out.println("调用 search_nodes：" +
            JsonUtil.toJson(connection.callTool("search_nodes", new HashMap<>())));
    }

    /**
     * 连接 PostgreSQL MCP 服务器
     * 让 Agent 能够直接查询数据库，无需手写 SQL 查询代码
     */
    @Test
    public void mcpPostgresConnect() throws Exception {
        ServerConfig config = new ServerConfig();
        config.command = "npx";
        config.args = List.of(
            "-y",
            "@modelcontextprotocol/server-postgres",
            "postgresql://myuser:123456@localhost:5432/mydb"  // 替换为实际连接串
        );
        config.env = new HashMap<>();

        McpServerConnection connection = new McpServerConnection("postgres-server", config);
        connection.connect();

        System.out.println("=== PostgreSQL MCP 服务器 ===");
        System.out.println("连接状态：" + connection.isConnected());
        System.out.println("可用工具：" + JsonUtil.toJson(connection.listTools()));

        // 有了 MCP，Agent 可以直接执行 SQL，无需你写任何 JDBC 代码
        // connection.callTool("query", Map.of("sql", "SELECT * FROM users LIMIT 5"));
    }
}
