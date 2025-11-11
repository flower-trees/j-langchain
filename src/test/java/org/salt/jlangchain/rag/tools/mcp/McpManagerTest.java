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
import org.salt.jlangchain.utils.JsonUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.junit4.SpringRunner;

import java.util.Map;

@RunWith(SpringRunner.class)
@SpringBootTest(classes = TestApplication.class)
@SpringBootConfiguration
public class McpManagerTest {

    @Autowired
    private McpManager mcpManager;

    @Test
    public void manifest() {
        System.out.println(JsonUtil.toJson(mcpManager.manifest()));
    }

    @Test
    public void manifestForInput() {
        System.out.println(JsonUtil.toJson(mcpManager.manifestForInput()));
    }

    @Test
    public void run() throws Exception {
        System.out.println(JsonUtil.toJson(mcpManager.run("default", "get_export_ip", Map.of())));
    }

    @Test
    public void runForInput() throws Exception {
        System.out.println(JsonUtil.toJson(mcpManager.runForInput("default", "get_export_ip", Map.of())));
    }
}