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

package org.salt.jlangchain.core.agent;

import lombok.extern.slf4j.Slf4j;
import org.salt.function.flow.FlowInstance;
import org.salt.function.flow.Info;
import org.salt.function.flow.context.ContextBus;
import org.salt.jlangchain.core.ChainActor;
import org.salt.jlangchain.core.handler.TranslateHandler;
import org.salt.jlangchain.core.llm.BaseChatModel;
import org.salt.jlangchain.core.message.AIMessage;
import org.salt.jlangchain.core.message.BaseMessage;
import org.salt.jlangchain.core.message.MessageType;
import org.salt.jlangchain.core.message.ToolMessage;
import org.salt.jlangchain.core.parser.StrOutputParser;
import org.salt.jlangchain.core.parser.generation.ChatGeneration;
import org.salt.jlangchain.core.prompt.chat.ChatPromptTemplate;
import org.salt.jlangchain.core.prompt.value.ChatPromptValue;
import org.salt.jlangchain.rag.tools.Tool;
import org.salt.jlangchain.rag.tools.annotation.ToolScanner;
import org.salt.jlangchain.rag.tools.mcp.McpClient;
import org.salt.jlangchain.rag.tools.mcp.McpManager;
import org.salt.jlangchain.rag.tools.mcp.tool.ToolDesc;
import org.salt.jlangchain.ai.common.param.AiChatInput;
import org.salt.jlangchain.ai.common.param.AiChatOutput;
import org.salt.jlangchain.utils.JsonUtil;
import org.springframework.util.CollectionUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * MCP Agent executor (Function Calling mode).
 *
 * <p>Uses the model's native Function Calling capability (structured JSON tool calls)
 * rather than ReAct text parsing. Suitable for MCP tools and any model that supports
 * the {@code tools} parameter (OpenAI, Doubao, Qwen, etc.).
 *
 * <p>Loop: LLM → ToolCall? → execute tool → append ToolMessage → LLM → ... → final answer
 *
 * <pre>{@code
 * ChatGeneration result = McpAgentExecutor.builder(chainActor)
 *     .llm(ChatAliyun.builder().model("qwen-plus").temperature(0f).build())
 *     .tools(mcpManager, "default")    // load tools from McpManager group
 *     .systemPrompt("你是一个智能助手")
 *     .build()
 *     .invoke("帮我查一下公网 IP");
 * }</pre>
 */
@Slf4j
public class McpAgentExecutor {

    private final FlowInstance agentChain;
    private final ChainActor chainActor;

    private McpAgentExecutor(ChainActor chainActor, FlowInstance agentChain) {
        this.chainActor = chainActor;
        this.agentChain = agentChain;
    }

    /**
     * Execute the agent with the given user question.
     */
    public ChatGeneration invoke(String input) {
        return chainActor.invoke(agentChain, Map.of("input", input));
    }

    public static Builder builder(ChainActor chainActor) {
        return new Builder(chainActor);
    }

    public static class Builder {

        private final ChainActor chainActor;
        private BaseChatModel llm;
        private List<Tool> tools = new ArrayList<>();
        private String systemPrompt;
        private int maxIterations = 10;
        private Consumer<String> toolCallLogger;
        private Consumer<String> observationLogger;
        // optional per-request authorization supplier (e.g. for MCP HTTP calls)
        private java.util.function.Supplier<String> authorizationSupplier;

        private Builder(ChainActor chainActor) {
            this.chainActor = chainActor;
        }

        public Builder llm(BaseChatModel llm) {
            this.llm = llm;
            return this;
        }

        public Builder tools(Tool... tools) {
            this.tools.addAll(List.of(tools));
            return this;
        }

        public Builder tools(List<Tool> tools) {
            this.tools.addAll(tools);
            return this;
        }

        /** Scan {@code toolsProvider} for {@link org.salt.jlangchain.rag.tools.annotation.AgentTool}-annotated methods. */
        public Builder tools(Object toolsProvider) {
            this.tools.addAll(ToolScanner.scan(toolsProvider));
            return this;
        }

        /**
         * Load tools from a McpManager group (HTTP API mode).
         *
         * @param mcpManager the MCP manager instance
         * @param group      the tool group name (key in mcp.config.json)
         */
        public Builder tools(McpManager mcpManager, String group) {
            this.tools.addAll(mcpManager.toTools(group));
            return this;
        }

        /**
         * Load tools from a McpClient server (NPX / SSE process mode).
         *
         * <p>Calls {@link McpClient#listAllTools()} for the given server name to obtain the
         * tool schema, and wraps {@link McpClient#callTool} as each tool's execution function.
         *
         * @param mcpClient  the MCP client instance
         * @param serverName the server name (key in mcp.server.config.json)
         */
        public Builder tools(McpClient mcpClient, String serverName) {
            List<ToolDesc> descs = mcpClient.listAllTools().getOrDefault(serverName, List.of());
            this.tools.addAll(descs.stream()
                .map(desc -> Tool.builder()
                    .name(desc.getName())
                    .description(desc.getDescription() != null ? desc.getDescription() : desc.getName())
                    .params(schemaToParams(desc.getInputSchema()))
                    .func(args -> {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> argsMap = (args instanceof Map) ? (Map<String, Object>) args : Map.of();
                        var result = mcpClient.callTool(serverName, desc.getName(), argsMap);
                        if (result == null) return "";
                        return result.isError
                            ? "Error: " + result.getFirstText()
                            : (result.getFirstText() != null ? result.getFirstText() : "");
                    })
                    .build())
                .collect(Collectors.toList()));
            return this;
        }

        /**
         * Extract a "paramA: typeA, paramB: typeB" string from a JSON Schema object.
         * Handles the standard {@code {"type":"object","properties":{...}}} shape.
         */
        @SuppressWarnings("unchecked")
        private static String schemaToParams(Object inputSchema) {
            if (inputSchema == null) return "";
            try {
                Map<String, Object> schema = (Map<String, Object>) inputSchema;
                Object propsObj = schema.get("properties");
                if (!(propsObj instanceof Map)) return "";
                Map<String, Object> props = (Map<String, Object>) propsObj;
                return props.entrySet().stream()
                    .map(e -> {
                        String pName = e.getKey();
                        String pType = "String";
                        if (e.getValue() instanceof Map<?, ?> pDef) {
                            Object t = pDef.get("type");
                            if (t != null) pType = switch (t.toString()) {
                                case "integer" -> "int";
                                case "number"  -> "double";
                                case "boolean" -> "boolean";
                                default        -> "String";
                            };
                        }
                        return pName + ": " + pType;
                    })
                    .collect(Collectors.joining(", "));
            } catch (Exception e) {
                return "";
            }
        }

        public Builder systemPrompt(String systemPrompt) {
            this.systemPrompt = systemPrompt;
            return this;
        }

        public Builder maxIterations(int maxIterations) {
            this.maxIterations = maxIterations;
            return this;
        }

        /** Hook called with the tool name + arguments JSON before execution. */
        public Builder onToolCall(Consumer<String> logger) {
            this.toolCallLogger = logger;
            return this;
        }

        /** Hook called with the observation string after tool execution. */
        public Builder onObservation(Consumer<String> logger) {
            this.observationLogger = logger;
            return this;
        }

        /**
         * Supplier for a per-request authorization token (e.g. Bearer token for MCP HTTP calls).
         * Called once per tool execution.
         */
        public Builder authorization(java.util.function.Supplier<String> supplier) {
            this.authorizationSupplier = supplier;
            return this;
        }

        public McpAgentExecutor build() {
            if (llm == null) throw new IllegalStateException("llm must be set");
            if (tools == null || tools.isEmpty()) throw new IllegalStateException("at least one tool must be provided");

            // ── 1. Build tool lookup map and AiChatInput.Tool list for the LLM ──
            Map<String, Tool> toolMap = new java.util.HashMap<>();
            List<AiChatInput.Tool> aiTools = new ArrayList<>();
            for (Tool t : tools) {
                toolMap.put(t.getName(), t);
                aiTools.add(toAiTool(t));
            }
            llm.setTools(aiTools);

            // ── 2. Build prompt (system + user placeholder) ──
            List<BaseMessage> messages = new ArrayList<>();
            if (systemPrompt != null && !systemPrompt.isBlank()) {
                messages.add(BaseMessage.fromMessage(MessageType.SYSTEM.getCode(), systemPrompt));
            }
            messages.add(BaseMessage.fromMessage(MessageType.HUMAN.getCode(), "${input}"));
            var prompt = ChatPromptTemplate.fromMessages(messages);

            // ── 3. Loop condition: continue while LLM returns tool calls ──
            int maxIter = this.maxIterations;
            Function<Integer, Boolean> shouldContinue = i -> {
                if (i >= maxIter) return false;
                if (i == 0) return true;
                AIMessage aiMessage = ContextBus.get().getResult(llm.getNodeId());
                if (aiMessage instanceof ToolMessage toolMessage) {
                    return !CollectionUtils.isEmpty(toolMessage.getToolCalls());
                }
                return false;
            };

            // ── 4. Tool executor: parse ToolCall JSON → execute Tool → append ToolMessage ──
            Function<Object, Boolean> isToolCall = msg ->
                msg instanceof ToolMessage tm && !CollectionUtils.isEmpty(tm.getToolCalls());

            Map<String, Tool> toolMapFinal = toolMap;
            Consumer<String> tcLogger = this.toolCallLogger;
            Consumer<String> obsLogger = this.observationLogger;
            // reserved for future per-call MCP authorization token injection
            @SuppressWarnings("unused")
            java.util.function.Supplier<String> authSupplier = this.authorizationSupplier;

            TranslateHandler<Object, AIMessage> executeTool = new TranslateHandler<>(msg -> {
                ToolMessage toolMessage = (ToolMessage) msg;
                ChatPromptValue chatPromptValue = ContextBus.get().getResult(prompt.getNodeId());

                for (AiChatOutput.ToolCall toolCall : toolMessage.getToolCalls()) {
                    String toolName = toolCall.getFunction().getName();
                    String argsJson = toolCall.getFunction().getArguments();

                    if (tcLogger != null) tcLogger.accept(toolName + " " + argsJson);
                    else log.debug("Tool call: {} args={}", toolName, argsJson);

                    Tool tool = toolMapFinal.get(toolName);
                    String observation;
                    if (tool == null) {
                        observation = "Tool '" + toolName + "' not found.";
                        log.warn("Tool not found: {}", toolName);
                    } else {
                        try {
                            // parse arguments JSON → Map, then pass to func
                            @SuppressWarnings("unchecked")
                            Map<String, Object> argsMap = JsonUtil.fromJson(argsJson, Map.class);
                            Object raw = tool.getFunc().apply(argsMap != null ? argsMap : Map.of());
                            observation = raw != null ? raw.toString() : "";
                        } catch (Exception e) {
                            observation = "Tool execution error: " + e.getMessage();
                            log.error("Tool execution failed: {}", toolName, e);
                        }
                    }

                    if (obsLogger != null) obsLogger.accept(observation);
                    else log.debug("Observation: {}", observation);

                    chatPromptValue.getMessages().add(
                        BaseMessage.fromMessage(MessageType.TOOL.getCode(), observation,
                            toolName, toolCall.getId())
                    );
                }
                return chatPromptValue;
            });

            // ── 5. Assemble the chain ──
            FlowInstance agentChain = chainActor.builder()
                .next(prompt)
                .loop(
                    shouldContinue,
                    llm,
                    chainActor.builder()
                        .next(
                            Info.c(isToolCall, executeTool),
                            Info.c(msg -> ContextBus.get().getResult(llm.getNodeId()))
                        )
                        .build()
                )
                .next(new StrOutputParser())
                .build();

            return new McpAgentExecutor(chainActor, agentChain);
        }

        /** Convert a j-langchain Tool to the AiChatInput.Tool format the LLM expects. */
        private static AiChatInput.Tool toAiTool(Tool tool) {
            AiChatInput.Tool aiTool = new AiChatInput.Tool();
            aiTool.setType("function");
            aiTool.setFunction(new AiChatInput.Tool.FunctionTool());
            aiTool.getFunction().setName(tool.getName());
            aiTool.getFunction().setDescription(tool.getDescription());
            // Build a minimal JSON Schema from the params string
            aiTool.getFunction().setParameters(buildSchema(tool.getParams()));
            return aiTool;
        }

        /**
         * Build a minimal OpenAI-compatible JSON Schema from the Tool params string.
         * Params format: "paramA: TypeA, paramB: TypeB"
         */
        private static Map<String, Object> buildSchema(String params) {
            Map<String, Object> properties = new java.util.LinkedHashMap<>();
            List<String> required = new ArrayList<>();

            if (params != null && !params.isBlank()) {
                for (String part : params.split(",")) {
                    String[] kv = part.trim().split(":", 2);
                    if (kv.length == 2) {
                        String name = kv[0].trim();
                        String type = kv[1].trim().toLowerCase();
                        String jsonType = switch (type) {
                            case "int", "integer", "long" -> "integer";
                            case "double", "float", "number" -> "number";
                            case "boolean", "bool" -> "boolean";
                            default -> "string";
                        };
                        properties.put(name, Map.of("type", jsonType));
                        required.add(name);
                    }
                }
            }

            return Map.of(
                "type", "object",
                "properties", properties,
                "required", required
            );
        }
    }
}
