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
import org.salt.jlangchain.ai.common.param.AiChatInput;
import org.salt.jlangchain.ai.common.param.AiChatOutput;
import org.salt.jlangchain.core.BaseRunnable;
import org.salt.jlangchain.core.ChainActor;
import org.salt.jlangchain.core.handler.TranslateHandler;
import org.salt.jlangchain.core.history.HistoryInfos;
import org.salt.jlangchain.core.history.storage.AgentTaskStorage;
import org.salt.jlangchain.core.history.storage.ConversationStorage;
import org.salt.jlangchain.core.llm.BaseChatModel;
import org.salt.jlangchain.core.message.*;
import org.salt.jlangchain.core.parser.StrOutputParser;
import org.salt.jlangchain.core.parser.generation.ChatGeneration;
import org.salt.jlangchain.core.prompt.chat.ChatPromptTemplate;
import org.salt.jlangchain.core.prompt.value.ChatPromptValue;
import org.salt.jlangchain.rag.tools.Tool;
import org.salt.jlangchain.rag.tools.annotation.ToolScanner;
import org.salt.jlangchain.rag.tools.mcp.McpClient;
import org.salt.jlangchain.rag.tools.mcp.McpManager;
import org.salt.jlangchain.rag.tools.mcp.tool.ToolDesc;
import org.salt.jlangchain.utils.JsonUtil;
import org.springframework.util.CollectionUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * MCP Agent executor (Function Calling mode).
 *
 * <p>Uses the model's native Function Calling capability (structured JSON tool calls)
 * rather than ReAct text parsing. Suitable for MCP tools and any model that supports
 * the {@code tools} parameter (OpenAI, Doubao, Qwen, etc.).
 *
 * <p>Loop: LLM → ToolCall? → execute tool → record in AgentTaskContext → LLM → ... → final answer
 *
 * <p>AgentTaskContext manages a sliding window of recent steps. When the window exceeds
 * {@code windowSize}, the oldest step is compressed into {@code earlyStepsSummary} and prepended
 * to the system message, preventing unbounded context growth over long task loops.
 *
 * <pre>{@code
 * ChatGeneration result = McpAgentExecutor.builder(chainActor)
 *     .llm(ChatAliyun.builder().model("qwen-plus").temperature(0f).build())
 *     .tools(mcpManager, "default")
 *     .systemPrompt("You are an intelligent assistant")
 *     .windowSize(5)                    // keep last 5 steps; compress older ones
 *     .agentTaskStorage(taskStorage)    // optional: persist steps for debugging
 *     .build()
 *     .invoke("Can you help me check the public IP address");
 * }</pre>
 */
@Slf4j
public class McpAgentExecutor extends BaseRunnable<ChatGeneration, Object> {

    private final FlowInstance agentChain;
    private final ChainActor chainActor;

    /** Carries the per-invocation AgentTaskContext into the lambda handlers. */
    private final ThreadLocal<AgentTaskContext> contextHolder;

    // Configuration captured for invoke()-level logic
    private final String systemPrompt;
    private final int windowSize;
    private final BaseChatModel summarizer;
    private final AgentTaskStorage agentTaskStorage;
    private final ConversationStorage conversationStorage;
    private final Long appId;
    private final Long userId;
    private final Long sessionId;
    private final String parentId;

    private McpAgentExecutor(ChainActor chainActor, FlowInstance agentChain,
                              ThreadLocal<AgentTaskContext> contextHolder,
                              String systemPrompt, int windowSize, BaseChatModel summarizer,
                              AgentTaskStorage agentTaskStorage,
                              ConversationStorage conversationStorage,
                              Long appId, Long userId, Long sessionId, String parentId) {
        this.chainActor = chainActor;
        this.agentChain = agentChain;
        this.contextHolder = contextHolder;
        this.systemPrompt = systemPrompt;
        this.windowSize = windowSize;
        this.summarizer = summarizer;
        this.agentTaskStorage = agentTaskStorage;
        this.conversationStorage = conversationStorage;
        this.appId = appId;
        this.userId = userId;
        this.sessionId = sessionId;
        this.parentId = parentId;
    }

    /**
     * Execute the agent with the given user question (node-compatible entry point).
     *
     * <p>Creates an {@link AgentTaskContext} for this invocation, runs the agent loop,
     * and optionally saves the final (question, answer) turn to {@link ConversationStorage}.
     */
    @Override
    public ChatGeneration invoke(Object input) {
        String question = input.toString();
        AgentTaskContext ctx = AgentTaskContext.create(question, systemPrompt, windowSize, parentId);
        contextHolder.set(ctx);
        try {
            ChatGeneration result = chainActor.invoke(agentChain, Map.of("input", question));
            if (conversationStorage != null && result != null) {
                List<BaseMessage> msgs = List.of(
                        HumanMessage.builder().content(question).build(),
                        AIMessage.builder().content(result.getText()).build()
                );
                conversationStorage.append(appId != null ? appId : 0L,
                        userId != null ? userId : 0L,
                        sessionId != null ? sessionId : 0L,
                        HistoryInfos.builder().parentId(parentId).messages(msgs).build());
            }
            return result;
        } finally {
            contextHolder.remove();
        }
    }

    /**
     * Execute the agent with the given user question.
     */
    public ChatGeneration invoke(String input) {
        return invoke((Object) input);
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
        private int windowSize = Integer.MAX_VALUE;
        private BaseChatModel summarizer;
        private AgentTaskStorage agentTaskStorage;
        private ConversationStorage conversationStorage;
        private Long appId;
        private Long userId;
        private Long sessionId;
        private String parentId;
        private Consumer<String> llmConsumer;
        private Consumer<String> toolCallConsumer;
        private Consumer<String> observationConsumer;
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
         */
        public Builder tools(McpManager mcpManager, String group) {
            this.tools.addAll(mcpManager.toTools(group));
            return this;
        }

        /**
         * Load tools from a McpClient server (NPX / SSE process mode).
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

        public Builder systemPrompt(String systemPrompt) {
            this.systemPrompt = systemPrompt;
            return this;
        }

        public Builder maxIterations(int maxIterations) {
            this.maxIterations = maxIterations;
            return this;
        }

        /**
         * Set the sliding-window size for AgentTaskContext.
         * When recentSteps exceeds this value the oldest step is compressed into earlyStepsSummary.
         * Default: {@code Integer.MAX_VALUE} (no compression, equivalent to previous behaviour).
         */
        public Builder windowSize(int windowSize) {
            this.windowSize = windowSize;
            return this;
        }

        /**
         * LLM used to produce earlyStepsSummary when the window is exceeded.
         * If not set, steps are concatenated as plain text instead.
         */
        public Builder summarizer(BaseChatModel summarizer) {
            this.summarizer = summarizer;
            return this;
        }

        /**
         * Optional storage for agent step records (debugging / auditing).
         * Each tool-call+result pair is appended as a {@code AGENT_STEP} HistoryInfos entry.
         */
        public Builder agentTaskStorage(AgentTaskStorage storage) {
            this.agentTaskStorage = storage;
            return this;
        }

        /**
         * Optional conversation storage.
         * When set, the final (question, answer) pair is saved as a {@code NORMAL} turn after the loop.
         */
        public Builder conversationStorage(ConversationStorage storage, Long appId, Long userId, Long sessionId) {
            this.conversationStorage = storage;
            this.appId = appId;
            this.userId = userId;
            this.sessionId = sessionId;
            return this;
        }

        /**
         * Optional parent record id (ConversationStorage entry or parent agent task id).
         * When set, step records written to AgentTaskStorage carry this as their parentId.
         */
        public Builder parentId(String parentId) {
            this.parentId = parentId;
            return this;
        }

        /** Hook called with the full LLM input payload before each model invocation. */
        public Builder onLlm(Consumer<String> consumer) {
            this.llmConsumer = consumer;
            return this;
        }

        /** Hook called with the tool name + arguments JSON before execution. */
        public Builder onToolCall(Consumer<String> consumer) {
            this.toolCallConsumer = consumer;
            return this;
        }

        /** Hook called with the observation string after tool execution. */
        public Builder onObservation(Consumer<String> consumer) {
            this.observationConsumer = consumer;
            return this;
        }

        /**
         * Supplier for a per-request authorization token (e.g. Bearer token for MCP HTTP calls).
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

            // ── 2. Build initial prompt template (system + user placeholder) ──
            List<BaseMessage> messages = new ArrayList<>();
            if (systemPrompt != null && !systemPrompt.isBlank()) {
                messages.add(BaseMessage.fromMessage(MessageType.SYSTEM.getCode(), systemPrompt));
            }
            messages.add(BaseMessage.fromMessage(MessageType.HUMAN.getCode(), "${input}"));
            var prompt = ChatPromptTemplate.fromMessages(messages);

            Consumer<String> llmConsumer = this.llmConsumer;
            TranslateHandler<ChatPromptValue, ChatPromptValue> emitBeforeLlm = new TranslateHandler<>(promptValue -> {
                if (llmConsumer != null && promptValue != null) {
                    llmConsumer.accept(formatChatPromptValue(promptValue));
                }
                return promptValue;
            });

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

            // ── 4. Tool executor ──
            Function<Object, Boolean> isToolCall = msg ->
                msg instanceof ToolMessage tm && !CollectionUtils.isEmpty(tm.getToolCalls());

            Map<String, Tool> toolMapFinal = toolMap;
            Consumer<String> toolCallConsumer = this.toolCallConsumer;
            Consumer<String> observationConsumer = this.observationConsumer;
            BaseChatModel summarizerFinal = this.summarizer;
            AgentTaskStorage agentTaskStorageFinal = this.agentTaskStorage;
            @SuppressWarnings("unused")
            java.util.function.Supplier<String> authSupplier = this.authorizationSupplier;

            // Shared context holder: created here, set/cleared in invoke() per call.
            ThreadLocal<AgentTaskContext> contextHolder = new ThreadLocal<>();

            TranslateHandler<Object, AIMessage> executeTool = new TranslateHandler<>(msg -> {
                ToolMessage toolMessage = (ToolMessage) msg;
                AgentTaskContext ctx = contextHolder.get();

                // Execute all tool calls and collect results
                List<BaseMessage> toolResults = new ArrayList<>();
                for (AiChatOutput.ToolCall toolCall : toolMessage.getToolCalls()) {
                    String toolName = toolCall.getFunction().getName();
                    String argsJson = toolCall.getFunction().getArguments();

                    if (toolCallConsumer != null) toolCallConsumer.accept(toolName + " " + argsJson);
                    else log.debug("Tool call: {} args={}", toolName, argsJson);

                    Tool tool = toolMapFinal.get(toolName);
                    String observation;
                    if (tool == null) {
                        observation = "Tool '" + toolName + "' not found.";
                        log.warn("Tool not found: {}", toolName);
                    } else {
                        try {
                            @SuppressWarnings("unchecked")
                            Map<String, Object> argsMap = JsonUtil.fromJson(argsJson, Map.class);
                            Object raw = tool.getFunc().apply(argsMap != null ? argsMap : Map.of());
                            observation = raw != null ? raw.toString() : "";
                        } catch (Exception e) {
                            observation = "Tool execution error: " + e.getMessage();
                            log.error("Tool execution failed: {}", toolName, e);
                        }
                    }

                    if (observationConsumer != null) observationConsumer.accept(observation);
                    else log.debug("Observation: {}", observation);

                    toolResults.add(BaseMessage.fromMessage(MessageType.TOOL.getCode(), observation,
                            toolName, toolCall.getId()));
                }

                if (ctx != null) {
                    // Record step in AgentTaskContext (handles window + optional storage)
                    ctx.addStep(AgentStep.ofFunctionCall(toolMessage, toolResults),
                            summarizerFinal, agentTaskStorageFinal);
                    // Return rebuilt ChatPromptValue for next iteration
                    return ctx.buildChatPromptValue();
                }

                // Fallback (context not set): original accumulate-in-place behaviour
                ChatPromptValue chatPromptValue = ContextBus.get().getResult(prompt.getNodeId());
                chatPromptValue.getMessages().add(toolMessage);
                chatPromptValue.getMessages().addAll(toolResults);
                return chatPromptValue;
            });

            // ── 5. Assemble the chain ──
            FlowInstance agentChain = chainActor.builder()
                .next(prompt)
                .loop(
                    shouldContinue,
                    emitBeforeLlm,
                    llm,
                    chainActor.builder()
                        .next(
                            Info.c(isToolCall, executeTool),
                            Info.c(msg -> ContextBus.get().getResult(llm.getNodeId()))
                        )
                        .build()
                )
                .next(
                    Info.c(output -> output instanceof ChatPromptValue, output -> {
                        throw new RuntimeException("Max iterations (" + maxIter + ") reached without a final answer.");
                    }),
                    Info.c(new StrOutputParser())
                )
                .build();

            return new McpAgentExecutor(chainActor, agentChain, contextHolder,
                    systemPrompt, windowSize, summarizer, agentTaskStorage,
                    conversationStorage, appId, userId, sessionId, parentId);
        }

        /** Convert a j-langchain Tool to the AiChatInput.Tool format the LLM expects. */
        private static AiChatInput.Tool toAiTool(Tool tool) {
            AiChatInput.Tool aiTool = new AiChatInput.Tool();
            aiTool.setType("function");
            aiTool.setFunction(new AiChatInput.Tool.FunctionTool());
            aiTool.getFunction().setName(tool.getName());
            aiTool.getFunction().setDescription(tool.getDescription());
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

        private static String formatChatPromptValue(ChatPromptValue promptValue) {
            if (promptValue == null || CollectionUtils.isEmpty(promptValue.getMessages())) {
                return "";
            }
            return promptValue.getMessages().stream()
                .map(message -> {
                    String role = message.getRole() != null ? message.getRole() : "unknown";
                    String content = message.getContent() != null ? message.getContent() : "";
                    return role + ": " + content;
                })
                .collect(Collectors.joining("\n"));
        }
    }
}
