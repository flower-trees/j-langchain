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

package org.salt.jlangchain.ai.chat.openai.param;

import lombok.Data;

import java.util.List;
import java.util.Map;

@Data
public class OpenAIRequest {

    // Basic model parameters
    private String model;
    private List<Message> messages;
    private boolean stream;
    private List<String> input;

    // Generation parameters
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
    private String user; // User identifier

    // Embedding related parameters
    private String dimension; // Embedding dimension for Aliyun
    private String encodingFormat; // Encoding format, e.g. "float" or "base64"

    // MCP related extensions
    private McpConfig mcpConfig;
    private List<Tool> tools; // Tool calls
    private String toolChoice; // Tool choice strategy
    private Boolean parallelToolCalls; // Whether to call tools in parallel

    // Response format control
    private ResponseFormat responseFormat;
    private String responseSchema; // JSON Schema

    // Advanced parameters
    private Integer seed; // Random seed
    private Map<String, Object> logitBias; // Token bias
    private String serviceTier; // Service tier

    @Data
    public static class Message {
        private String role;
        private String content;
        private String name; // Message sender name
        private List<ToolCall> toolCalls; // Tool calls
        private String toolCallId; // Tool call ID
        private Map<String, Object> metadata; // Message metadata
    }

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

    // Builder pattern support
    public static OpenAIRequestBuilder builder() {
        return new OpenAIRequestBuilder();
    }

    public static class OpenAIRequestBuilder {
        private OpenAIRequest request = new OpenAIRequest();

        public OpenAIRequestBuilder model(String model) {
            request.setModel(model);
            return this;
        }

        public OpenAIRequestBuilder messages(List<Message> messages) {
            request.setMessages(messages);
            return this;
        }

        public OpenAIRequestBuilder stream(boolean stream) {
            request.setStream(stream);
            return this;
        }

        public OpenAIRequestBuilder temperature(Float temperature) {
            request.setTemperature(temperature);
            return this;
        }

        public OpenAIRequestBuilder maxTokens(Integer maxTokens) {
            request.setMaxTokens(maxTokens);
            return this;
        }

        public OpenAIRequestBuilder tools(List<Tool> tools) {
            request.setTools(tools);
            return this;
        }

        public OpenAIRequestBuilder toolChoice(String toolChoice) {
            request.setToolChoice(toolChoice);
            return this;
        }

        public OpenAIRequestBuilder mcpConfig(McpConfig mcpConfig) {
            request.setMcpConfig(mcpConfig);
            return this;
        }

        public OpenAIRequestBuilder responseFormat(ResponseFormat responseFormat) {
            request.setResponseFormat(responseFormat);
            return this;
        }

        public OpenAIRequest build() {
            return request;
        }
    }

    // Convenience methods
    public boolean hasTools() {
        return tools != null && !tools.isEmpty();
    }

    public boolean isMcpEnabled() {
        return mcpConfig != null;
    }

    public boolean isEmbeddingRequest() {
        return input != null && !input.isEmpty();
    }

    public boolean isChatCompletionRequest() {
        return messages != null && !messages.isEmpty();
    }
}
