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

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class AiChatOutput {

    private String id;

    private List<Message> messages;

    private String code;
    private String message;

    private List<DataObject> data;

    // MCP related response fields
    private McpResponse mcpResponse;

    @Data
    public static class Message {
        private String role;
        private Object content;
        private String type;
        private List<ToolCall> toolCalls; // Tool calls in response
        private FunctionCall functionCall; // Deprecated function call format
        private String name; // Message name
        private Map<String, Object> metadata; // Additional message metadata
    }

    @Data
    public static class DataObject {
        private String object;
        private int index;
        private List<Float> embedding;
    }

    @Data
    public static class ToolCall {
        private String id;
        private String type; // "function"
        private Function function;
        private int index; // For streaming responses

        @Data
        public static class Function {
            private String name;
            private String arguments; // JSON string
        }
    }

    @Data
    public static class FunctionCall {
        private String name;
        private String arguments; // JSON string
    }
    @Data
    public static class McpResponse {
        private ServerInfo serverInfo;
        private List<ToolResult> toolResults;
        private String status; // "success", "error", "partial"
        private Long executionTime; // Execution time in milliseconds

        @Data
        public static class ServerInfo {
            private String name;
            private String version;
            private String protocolVersion;
            private List<String> capabilities;
        }

        @Data
        public static class ToolResult {
            private String toolName;
            private String status; // "success", "error"
            private Object result; // Tool execution result
            private String error; // Error message if failed
            private Long executionTime;
        }
    }

    public static AiChatOutputBuilder builder() {
        return new AiChatOutputBuilder();
    }

    public static final class AiChatOutputBuilder {
        private String id;
        private List<Message> messages;
        private String code;
        private String message;
        private List<DataObject> data;
        private McpResponse mcpResponse;

        private AiChatOutputBuilder() {
        }

        public AiChatOutputBuilder id(String id) {
            this.id = id;
            return this;
        }

        public AiChatOutputBuilder messages(List<Message> messages) {
            this.messages = messages;
            return this;
        }

        public AiChatOutputBuilder code(String code) {
            this.code = code;
            return this;
        }

        public AiChatOutputBuilder message(String message) {
            this.message = message;
            return this;
        }

        public AiChatOutputBuilder data(List<DataObject> data) {
            this.data = data;
            return this;
        }

        public AiChatOutputBuilder mcpResponse(McpResponse mcpResponse) {
            this.mcpResponse = mcpResponse;
            return this;
        }

        public AiChatOutput build() {
            return new AiChatOutput(this.id, this.messages, this.code, this.message, this.data, this.mcpResponse);
        }

        @Override
        public String toString() {
            return "AiChatOutput.AiChatOutputBuilder(id=" + this.id + ", messages=" + this.messages + ", code=" + this.code + ", message=" + this.message + ", data=" + this.data + ", mcpResponse=" + this.mcpResponse + ")";
        }
    }
}
