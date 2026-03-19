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

package org.salt.jlangchain.ai.common.param;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.salt.jlangchain.utils.GroceryUtil;

import java.util.List;
import java.util.Map;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class AiChatInput {

    @JsonIgnore
    protected String id = GroceryUtil.generateId();
    @JsonIgnore
    @JsonProperty("parent_id")
    protected String parentId;

    private String model;

    @JsonProperty("bot_id")
    private String botId;

    @JsonProperty("user_id")
    private String userId;

    private List<Message> messages;
    private boolean stream = false;

    private List<String> input;

    @Data
    @AllArgsConstructor
    public static class Message {

        public Message(String role, String content) {
            this.role = role;
            this.content = content;
        }

        public Message(String role, String content, String name, String toolCallId) {
            this.role = role;
            this.content = content;
            this.name = name;
            this.toolCallId = toolCallId;
        }

        private String role;
        private String content;
        private String name; // Message sender name
        private List<ToolCall> toolCalls; // Tool calls
        private String toolCallId; // Tool call ID
        private Map<String, Object> metadata; // Message metadata
    }

    private Float temperature;
    private Float topP;
    private Integer maxTokens;
    private Float presencePenalty;
    private Float frequencyPenalty;
    private List<String> stop;
    private Integer n; // Number of completions to generate
    private String suffix;
    private Integer logprobs;
    private Integer topLogprobs;
    private Boolean echo;

    private int vectorSize;

    private String key;

    // MCP related extensions
    private McpConfig mcpConfig;
    private List<Tool> tools; // Tool calls
    private String toolChoice; // Tool choice strategy
    private Boolean parallelToolCalls; // Whether to call tools in parallel

    // Response format control
    private ResponseFormat responseFormat;
    private String responseSchema; // JSON Schema

    @Data
    public static class Tool {
        private String type; // "function" etc.
        private FunctionTool function;

        @Data
        public static class FunctionTool {
            private String name;
            private String description;
            private Map<String, Object> parameters; // JSON Schema parameter definition
            private Boolean strict; // Whether to use strict mode
        }
    }

    @Data
    public static class ToolCall {
        private String id;
        private String type;
        private FunctionCall function;

        @Data
        public static class FunctionCall {
            private String name;
            private String arguments; // JSON string
        }
    }

    @Data
    public static class McpConfig {
        private String serverUrl; // MCP server URL
        private String version; // MCP protocol version
        private Map<String, String> capabilities; // Supported capabilities
        private AuthConfig auth; // Authentication configuration
        private Integer timeout; // Timeout in milliseconds
        private Integer retryCount; // Retry count
        private List<String> enabledTools; // List of enabled tools

        @Data
        public static class AuthConfig {
            private String type; // "bearer", "api_key", etc.
            private String token;
            private Map<String, String> headers; // Additional authentication headers
        }
    }

    @Data
    public static class ResponseFormat {
        private String type; // "text", "json_object", "json_schema"
        private JsonSchema jsonSchema;

        @Data
        public static class JsonSchema {
            private String name;
            private Map<String, Object> schema;
            private Boolean strict;
        }
    }
}
