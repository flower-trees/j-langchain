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
import org.salt.jlangchain.core.common.CallInfo;
import org.salt.jlangchain.core.handler.TranslateHandler;
import org.salt.jlangchain.core.history.memory.ConversationMemoryStorerBase;
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
 * <p>Context management is delegated to {@link AgentTaskContext}. Inject a
 * {@link SlidingWindowContext} for sliding-window compression, or omit {@code context()}
 * to use the default {@link FullContext} (no compression).
 *
 * <pre>{@code
 * ChatGeneration result = McpAgentExecutor.builder(chainActor)
 *     .llm(ChatAliyun.builder().model("qwen-plus").temperature(0f).build())
 *     .tools(mcpManager, "default")
 *     .systemPrompt("You are an intelligent assistant")
 *     .context(SlidingWindowContext.builder().windowSize(5).build())
 *     .build()
 *     .invoke("Can you help me check the public IP address");
 * }</pre>
 */
@Slf4j
public class McpAgentExecutor extends BaseRunnable<ChatGeneration, Object> {

    private final FlowInstance agentChain;
    private final ChainActor chainActor;

    private McpAgentExecutor(ChainActor chainActor, FlowInstance agentChain) {
        this.chainActor = chainActor;
        this.agentChain = agentChain;
    }

    @Override
    public ChatGeneration invoke(Object input) {
        return (ChatGeneration) chainActor.invoke(agentChain, Map.of("input", input.toString()));
    }

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
        private AgentContext context;
        private ConversationMemoryStorerBase conversationStorer;
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

        public Builder tools(Object toolsProvider) {
            if (toolsProvider instanceof Tool tool) {
                this.tools.add(tool);
            } else {
                this.tools.addAll(ToolScanner.scan(toolsProvider));
            }
            return this;
        }

        public Builder tools(McpManager mcpManager, String group) {
            this.tools.addAll(mcpManager.toTools(group));
            return this;
        }

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

        /** Inject a context strategy. Defaults to {@link FullContext} if not set. */
        public Builder context(AgentContext context) {
            this.context = context;
            return this;
        }

        /** Optional: save the final (question, answer) turn to conversation history after the loop. */
        public Builder conversationStorer(ConversationMemoryStorerBase conversationStorer) {
            this.conversationStorer = conversationStorer;
            return this;
        }

        public Builder onLlm(Consumer<String> consumer) {
            this.llmConsumer = consumer;
            return this;
        }

        public Builder onToolCall(Consumer<String> consumer) {
            this.toolCallConsumer = consumer;
            return this;
        }

        public Builder onObservation(Consumer<String> consumer) {
            this.observationConsumer = consumer;
            return this;
        }

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

            AgentContext contextFinal = this.context != null ? this.context : FullContext.build();
            String systemPromptFinal = this.systemPrompt;
            ConversationMemoryStorerBase conversationStorerFinal = this.conversationStorer;
            Consumer<String> llmConsumer = this.llmConsumer;

            // First node: create per-invocation AgentTaskContext and put in ContextBus
            TranslateHandler<Object, Object> initContext = new TranslateHandler<>(input -> {
                String question = (input instanceof Map<?, ?> m && m.get("input") != null)
                        ? m.get("input").toString()
                        : input.toString();
                AgentTaskContext ctx = contextFinal.create(question, systemPromptFinal);
                ContextBus.get().putTransmit(CallInfo.AGENT_TASK_CTX.name(), ctx);
                ContextBus.get().putTransmit(CallInfo.QUESTION.name(), question);
                return input;
            });

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
            @SuppressWarnings("unused")
            java.util.function.Supplier<String> authSupplier = this.authorizationSupplier;

            TranslateHandler<Object, AIMessage> executeTool = new TranslateHandler<>(msg -> {
                ToolMessage toolMessage = (ToolMessage) msg;
                AgentTaskContext ctx = ContextBus.get().getTransmit(CallInfo.AGENT_TASK_CTX.name());

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

                ctx.addStep(AgentStep.ofFunctionCall(toolMessage, toolResults));
                return ctx.buildChatPromptValue();
            });

            // ── 5. Assemble the chain ──
            var chainBuilder = chainActor.builder()
                .next(initContext)
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
                );

            FlowInstance agentChain = conversationStorerFinal != null
                    ? chainBuilder.next(conversationStorerFinal).build()
                    : chainBuilder.build();

            return new McpAgentExecutor(chainActor, agentChain);
        }

        private static AiChatInput.Tool toAiTool(Tool tool) {
            AiChatInput.Tool aiTool = new AiChatInput.Tool();
            aiTool.setType("function");
            aiTool.setFunction(new AiChatInput.Tool.FunctionTool());
            aiTool.getFunction().setName(tool.getName());
            aiTool.getFunction().setDescription(tool.getDescription());
            aiTool.getFunction().setParameters(buildSchema(tool.getParams()));
            return aiTool;
        }

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
            return Map.of("type", "object", "properties", properties, "required", required);
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
