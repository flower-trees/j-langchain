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
public class OpenAIResponse {

    // Basic response fields
    private String id;
    private String object;
    private int created;
    private String model;
    private String systemFingerprint;

    // Chat completion response
    private List<Choice> choices;

    // Embedding response
    private List<DataObject> data;

    // Usage statistics
    private Usage usage;

    // Error information
    private Error error;

    // MCP related response fields
    private McpResponse mcpResponse;

    // Additional metadata
    private Map<String, Object> metadata;
    private String warnings;

    @Data
    public static class Choice {
        private int index;
        private Delta delta; // For streaming responses
        private Message message; // For non-streaming responses
        private Object logprobs; // Log probabilities
        private String finishReason; // "stop", "length", "tool_calls", "content_filter", "function_call"

        @Data
        public static class Delta {
            private String role;
            private String content;
            private List<ToolCall> toolCalls; // Tool calls in streaming
            private FunctionCall functionCall; // Deprecated function call format
        }

        @Data
        public static class Message {
            private String role;
            private String content;
            private List<ToolCall> toolCalls; // Tool calls in response
            private FunctionCall functionCall; // Deprecated function call format
            private String name; // Message name
            private Map<String, Object> metadata; // Additional message metadata
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
        public static class LogProbs {
            private List<TokenLogProb> tokens;
            private List<Integer> textOffset;

            @Data
            public static class TokenLogProb {
                private String token;
                private Double logprob;
                private List<Integer> bytes;
                private List<TopLogProb> topLogprobs;

                @Data
                public static class TopLogProb {
                    private String token;
                    private Double logprob;
                    private List<Integer> bytes;
                }
            }
        }
    }

    @Data
    public static class DataObject {
        private String object; // "embedding"
        private int index;
        private List<Float> embedding; // Embedding vector
        private Map<String, Object> metadata; // Additional embedding metadata
    }

    @Data
    public static class Usage {
        private int promptTokens;
        private int completionTokens;
        private int totalTokens;
        private PromptTokensDetails promptTokensDetails;
        private CompletionTokensDetails completionTokensDetails;

        @Data
        public static class PromptTokensDetails {
            private int cachedTokens;
        }

        @Data
        public static class CompletionTokensDetails {
            private int reasoningTokens;
        }
    }

    @Data
    public static class Error {
        private String message;
        private String type;
        private String param;
        private String code;
        private Map<String, Object> details; // Additional error details
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

    // Convenience methods for response type detection
    public boolean isChatCompletion() {
        return choices != null && !choices.isEmpty();
    }

    public boolean isEmbedding() {
        return data != null && !data.isEmpty();
    }

    public boolean isError() {
        return error != null;
    }

    public boolean isStreaming() {
        return choices != null && choices.stream().anyMatch(choice -> choice.getDelta() != null);
    }

    public boolean hasToolCalls() {
        if (choices == null) return false;
        return choices.stream().anyMatch(choice ->
                (choice.getMessage() != null && choice.getMessage().getToolCalls() != null && !choice.getMessage().getToolCalls().isEmpty()) ||
                        (choice.getDelta() != null && choice.getDelta().getToolCalls() != null && !choice.getDelta().getToolCalls().isEmpty())
        );
    }

    public boolean isMcpResponse() {
        return mcpResponse != null;
    }

    // Get first choice content (convenience method)
    public String getContent() {
        if (choices == null || choices.isEmpty()) return null;
        Choice firstChoice = choices.get(0);

        if (firstChoice.getMessage() != null) {
            return firstChoice.getMessage().getContent();
        } else if (firstChoice.getDelta() != null) {
            return firstChoice.getDelta().getContent();
        }

        return null;
    }

    // Get first choice finish reason (convenience method)
    public String getFinishReason() {
        if (choices == null || choices.isEmpty()) return null;
        return choices.get(0).getFinishReason();
    }

    // Get total tokens used (convenience method)
    public int getTotalTokens() {
        return usage != null ? usage.getTotalTokens() : 0;
    }

    // Check if response was truncated due to length
    public boolean wasTruncated() {
        return "length".equals(getFinishReason());
    }

    // Check if response was stopped due to content filter
    public boolean wasFiltered() {
        return "content_filter".equals(getFinishReason());
    }
}

