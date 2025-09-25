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

import org.junit.Test;
import org.junit.runner.RunWith;
import org.salt.jlangchain.TestApplication;
import org.salt.jlangchain.rag.tools.mcp.server.config.ServerConfig;
import org.salt.jlangchain.rag.tools.mcp.server.McpServerConnection;
import org.salt.jlangchain.utils.JsonUtil;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.junit4.SpringRunner;

import java.util.HashMap;
import java.util.List;

import static org.junit.Assert.*;

@RunWith(SpringRunner.class)
@SpringBootTest(classes = TestApplication.class)
@SpringBootConfiguration
public class McpServerConnectionTest {

    @Test
    public void testCreateConnection() throws Exception {
        ServerConfig config = new ServerConfig();
        config.command = "npx";
        config.args = List.of("-y", "@modelcontextprotocol/server-memory");
        config.env = new HashMap<>();

        McpServerConnection connection = new McpServerConnection("test-server", config);

        connection.connect();

        assertNotNull("Connection should not be null", connection);
        assertEquals("test-server", connection.getServerName());
        assertTrue("Should not be connected initially", connection.isConnected());
        assertNull("Should have no error initially", connection.getLastError());

        System.out.println("Connection status: " + JsonUtil.toJson(connection.listTools()));

        System.out.println("Call Tool:" + JsonUtil.toJson(connection.callTool("search_nodes", new HashMap<>())));
    }

    @Test
    public void testCreatePostgresConnection() throws Exception {
        ServerConfig config = new ServerConfig();
        config.command = "npx";
        config.args = List.of("-y", "@modelcontextprotocol/server-postgres", "postgresql://myuser:123456@localhost:5432/mydb");
        config.env = new HashMap<>();

        McpServerConnection connection = new McpServerConnection("postgres-server", config);

        connection.connect();

        assertNotNull("Connection should not be null", connection);
        assertEquals("postgres-server", connection.getServerName());
        assertTrue("Should not be connected initially", connection.isConnected());
        assertNull("Should have no error initially", connection.getLastError());

        System.out.println("Connection status: " + JsonUtil.toJson(connection.listTools()));
    }
}