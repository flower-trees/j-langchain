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
import lombok.Data;
import lombok.NoArgsConstructor;
import org.salt.jlangchain.utils.GroceryUtil;

import java.util.List;
import java.util.Map;

@Data
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

    @SuppressWarnings("all")
    public static class AiChatInputBuilder {
        private String id;
        private String parentId;
        private String model;
        private String botId;
        private String userId;
        private List<Message> messages;
        private boolean stream;
        private List<String> input;
        private Float temperature;
        private Float topP;
        private Integer maxTokens;
        private Float presencePenalty;
        private Float frequencyPenalty;
        private List<String> stop;
        private Integer n;
        private String suffix;
        private Integer logprobs;
        private Integer topLogprobs;
        private Boolean echo;
        private int vectorSize;
        private String key;
        private McpConfig mcpConfig;
        private List<Tool> tools;
        private String toolChoice;
        private Boolean parallelToolCalls;
        private ResponseFormat responseFormat;
        private String responseSchema;

        AiChatInputBuilder() {
        }

        @JsonIgnore
        public AiChatInputBuilder id(final String id) {
            this.id = id;
            return this;
        }

        @JsonIgnore
        @JsonProperty("parent_id")
        public AiChatInputBuilder parentId(final String parentId) {
            this.parentId = parentId;
            return this;
        }

        public AiChatInputBuilder model(final String model) {
            this.model = model;
            return this;
        }

        @JsonProperty("bot_id")
        public AiChatInputBuilder botId(final String botId) {
            this.botId = botId;
            return this;
        }

        @JsonProperty("user_id")
        public AiChatInputBuilder userId(final String userId) {
            this.userId = userId;
            return this;
        }

        public AiChatInputBuilder messages(final List<Message> messages) {
            this.messages = messages;
            return this;
        }

        public AiChatInputBuilder stream(final boolean stream) {
            this.stream = stream;
            return this;
        }

        public AiChatInputBuilder input(final List<String> input) {
            this.input = input;
            return this;
        }

        public AiChatInputBuilder temperature(final Float temperature) {
            this.temperature = temperature;
            return this;
        }

        public AiChatInputBuilder topP(final Float topP) {
            this.topP = topP;
            return this;
        }

        public AiChatInputBuilder maxTokens(final Integer maxTokens) {
            this.maxTokens = maxTokens;
            return this;
        }

        public AiChatInputBuilder presencePenalty(final Float presencePenalty) {
            this.presencePenalty = presencePenalty;
            return this;
        }

        public AiChatInputBuilder frequencyPenalty(final Float frequencyPenalty) {
            this.frequencyPenalty = frequencyPenalty;
            return this;
        }

        public AiChatInputBuilder stop(final List<String> stop) {
            this.stop = stop;
            return this;
        }

        public AiChatInputBuilder n(final Integer n) {
            this.n = n;
            return this;
        }

        public AiChatInputBuilder suffix(final String suffix) {
            this.suffix = suffix;
            return this;
        }

        public AiChatInputBuilder logprobs(final Integer logprobs) {
            this.logprobs = logprobs;
            return this;
        }

        public AiChatInputBuilder topLogprobs(final Integer topLogprobs) {
            this.topLogprobs = topLogprobs;
            return this;
        }

        public AiChatInputBuilder echo(final Boolean echo) {
            this.echo = echo;
            return this;
        }

        public AiChatInputBuilder vectorSize(final int vectorSize) {
            this.vectorSize = vectorSize;
            return this;
        }

        public AiChatInputBuilder key(final String key) {
            this.key = key;
            return this;
        }

        public AiChatInputBuilder mcpConfig(final McpConfig mcpConfig) {
            this.mcpConfig = mcpConfig;
            return this;
        }

        public AiChatInputBuilder tools(final List<Tool> tools) {
            this.tools = tools;
            return this;
        }

        public AiChatInputBuilder toolChoice(final String toolChoice) {
            this.toolChoice = toolChoice;
            return this;
        }

        public AiChatInputBuilder parallelToolCalls(final Boolean parallelToolCalls) {
            this.parallelToolCalls = parallelToolCalls;
            return this;
        }

        public AiChatInputBuilder responseFormat(final ResponseFormat responseFormat) {
            this.responseFormat = responseFormat;
            return this;
        }

        public AiChatInputBuilder responseSchema(final String responseSchema) {
            this.responseSchema = responseSchema;
            return this;
        }

        public AiChatInput build() {
            return new AiChatInput(this.id, this.parentId, this.model, this.botId, this.userId, this.messages, this.stream, this.input, this.temperature, this.topP, this.maxTokens, this.presencePenalty, this.frequencyPenalty, this.stop, this.n, this.suffix, this.logprobs, this.topLogprobs, this.echo, this.vectorSize, this.key, this.mcpConfig, this.tools, this.toolChoice, this.parallelToolCalls, this.responseFormat, this.responseSchema);
        }

        @Override
        public String toString() {
            return "AiChatInput.AiChatInputBuilder(id=" + this.id + ", parentId=" + this.parentId + ", model=" + this.model + ", botId=" + this.botId + ", userId=" + this.userId + ", messages=" + this.messages + ", stream=" + this.stream + ", input=" + this.input + ", temperature=" + this.temperature + ", topP=" + this.topP + ", maxTokens=" + this.maxTokens + ", presencePenalty=" + this.presencePenalty + ", frequencyPenalty=" + this.frequencyPenalty + ", stop=" + this.stop + ", n=" + this.n + ", suffix=" + this.suffix + ", logprobs=" + this.logprobs + ", topLogprobs=" + this.topLogprobs + ", echo=" + this.echo + ", vectorSize=" + this.vectorSize + ", key=" + this.key + ", mcpConfig=" + this.mcpConfig + ", tools=" + this.tools + ", toolChoice=" + this.toolChoice + ", parallelToolCalls=" + this.parallelToolCalls + ", responseFormat=" + this.responseFormat + ", responseSchema=" + this.responseSchema + ")";
        }
    }

    public static AiChatInputBuilder builder() {
        return new AiChatInputBuilder();
    }
}
