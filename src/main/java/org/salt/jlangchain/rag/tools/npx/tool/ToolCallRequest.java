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

package org.salt.jlangchain.rag.tools.npx.tool;

import java.util.Map;

public class ToolCallRequest {
    public String serverName;
    public String toolName;
    public Map<String, Object> arguments;

    public ToolCallRequest(String serverName, String toolName, Map<String, Object> arguments) {
        this.serverName = serverName;
        this.toolName = toolName;
        this.arguments = arguments;
    }
}