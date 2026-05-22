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
import org.salt.jlangchain.ai.common.param.AiTokenUsage;
import org.salt.jlangchain.core.BaseRunnable;
import org.salt.jlangchain.core.ChainActor;
import org.salt.jlangchain.core.agent.memory.*;
import org.salt.jlangchain.core.common.CallInfo;
import org.salt.jlangchain.core.handler.TranslateHandler;
import org.salt.jlangchain.core.history.memory.ConversationMemoryStorerBase;
import org.salt.jlangchain.core.llm.BaseChatModel;
import org.salt.jlangchain.core.message.*;
import org.salt.jlangchain.core.parser.StrOutputParser;
import org.salt.jlangchain.core.parser.generation.ChatGeneration;
import org.salt.jlangchain.core.prompt.chat.ChatPromptTemplate;
import org.salt.jlangchain.core.prompt.value.ChatPromptValue;
import org.salt.jlangchain.core.skill.Skill;
import org.salt.jlangchain.core.subagent.SubAgent;
import org.salt.jlangchain.rag.tools.Tool;
import org.salt.jlangchain.rag.tools.annotation.ToolScanner;
import org.salt.jlangchain.rag.tools.mcp.McpClient;
import org.salt.jlangchain.rag.tools.mcp.McpManager;
import org.salt.jlangchain.rag.tools.mcp.tool.ToolDesc;
import org.salt.jlangchain.utils.JsonUtil;
import org.springframework.util.CollectionUtils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
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
    private volatile AtomicBoolean stopSignal = new AtomicBoolean(false);
    private final ConversationMemoryStorerBase conversationStorer;

    private McpAgentExecutor(ChainActor chainActor, FlowInstance agentChain,
                              ConversationMemoryStorerBase conversationStorer) {
        this.chainActor = chainActor;
        this.agentChain = agentChain;
        this.conversationStorer = conversationStorer;
    }

    /** Stop the currently running invocation. Safe to call from any thread. */
    public void stop() {
        stopSignal.set(true);
    }

    // ── invoke overloads (all delegate to the core method) ───────────────────

    @Override
    public ChatGeneration invoke(Object input) {
        return invoke(input.toString(), null, null);
    }

    public ChatGeneration invoke(String input) {
        return invoke(input, null, null);
    }

    /** For Skill/SubAgent: reuse the parent's stop signal so a parent stop propagates down. */
    public ChatGeneration invoke(String input, AtomicBoolean externalSignal) {
        return invoke(input, externalSignal, null);
    }

    /** For resume: outer layer supplies a pre-loaded context; the agent uses it without knowing why. */
    public ChatGeneration invoke(String input, AgentTaskContext preloadedCtx) {
        return invoke(input, null, preloadedCtx);
    }

    /**
     * Core invoke method. {@code externalSignal} and {@code preloadedCtx} are both optional.
     * When {@code externalSignal} is provided the caller controls stopping (Skill/SubAgent propagation
     * or session-task management); otherwise a fresh signal is created and {@link #stop()} controls it.
     */
    public ChatGeneration invoke(String input, AtomicBoolean externalSignal, AgentTaskContext preloadedCtx) {
        this.stopSignal = externalSignal != null ? externalSignal : new AtomicBoolean(false);
        Map<String, Object> transmitMap = new HashMap<>();
        transmitMap.put(CallInfo.STOP_SIGNAL.name(), stopSignal);
        if (preloadedCtx != null) transmitMap.put(CallInfo.PRELOADED_CTX.name(), preloadedCtx);
        try {
            return (ChatGeneration) chainActor.invoke(agentChain, Map.of("input", input), transmitMap);
        } catch (AgentStoppedException e) {
            if (conversationStorer != null) conversationStorer.savePartial(e.getPartialContext());
            throw e;
        } catch (AgentPauseException e) {
            if (conversationStorer != null) conversationStorer.savePartial(e.getPartialContext());
            throw e;
        } catch (AgentAbortException e) {
            if (conversationStorer != null) conversationStorer.savePartial(e.getPartialContext());
            throw e;
        }
    }

    public static Builder builder(ChainActor chainActor) {
        return new Builder(chainActor);
    }

    public static class Builder {

        private final ChainActor chainActor;
        private BaseChatModel llm;
        private List<Tool> tools = new ArrayList<>();
        private List<Skill> skills = new ArrayList<>();
        private List<SubAgent> subAgents = new ArrayList<>();
        private Function<String, BaseChatModel> llmFactory;
        private String systemPrompt;
        private int maxIterations = 10;
        private int maxDurationSeconds = 0;
        private int maxConsecutiveToolFailures = 0;
        private int toolRetry = 0;
        private AgentContext context;
        private ConversationMemoryStorerBase conversationStorer;
        private Consumer<String> llmConsumer;
        private Consumer<String> toolCallConsumer;
        private Consumer<String> observationConsumer;
        private Consumer<AgentTokenUsageEvent> tokenUsageConsumer;
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

        public Builder skill(Skill skill) {
            this.skills.add(skill);
            return this;
        }

        public Builder skills(Skill... skills) {
            this.skills.addAll(List.of(skills));
            return this;
        }

        public Builder subAgent(SubAgent subAgent) {
            this.subAgents.add(subAgent);
            return this;
        }

        public Builder subAgents(SubAgent... subAgents) {
            this.subAgents.addAll(List.of(subAgents));
            return this;
        }

        /**
         * Provide a factory that converts a model name to a {@link BaseChatModel}.
         * Propagated to sub-agents whose AGENT.md specifies {@code model: <name>}
         * (any non-inherit value) and have no explicit LLM set.
         */
        public Builder llmFactory(Function<String, BaseChatModel> factory) {
            this.llmFactory = factory;
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

        /** Maximum wall-clock time for the entire loop. 0 means no limit. */
        public Builder maxDurationSeconds(int maxDurationSeconds) {
            this.maxDurationSeconds = maxDurationSeconds;
            return this;
        }

        /** Abort after this many consecutive rounds where every tool call failed. 0 means no limit. */
        public Builder maxConsecutiveToolFailures(int maxConsecutiveToolFailures) {
            this.maxConsecutiveToolFailures = maxConsecutiveToolFailures;
            return this;
        }

        /** Auto-retry a failing tool up to this many extra attempts before returning an error observation. 0 means no retry. */
        public Builder toolRetry(int toolRetry) {
            this.toolRetry = toolRetry;
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

        public Builder onTokenUsage(Consumer<AgentTokenUsageEvent> consumer) {
            this.tokenUsageConsumer = consumer;
            return this;
        }

        public Builder verbose(boolean enabled) {
            if (enabled) {
                this.llmConsumer         = msg -> System.out.println("[LLM]\n" + msg);
                this.toolCallConsumer    = tc  -> System.out.println("[ToolCall] " + tc);
                this.observationConsumer = obs -> System.out.println("[Observation] " + obs);
            } else {
                this.llmConsumer = null;
                this.toolCallConsumer = null;
                this.observationConsumer = null;
            }
            return this;
        }

        public Builder authorization(java.util.function.Supplier<String> supplier) {
            this.authorizationSupplier = supplier;
            return this;
        }

        public McpAgentExecutor build() {
            if (llm == null) throw new IllegalStateException("llm must be set");

            // Inject allowed parent tools into each skill, then register skill as a tool
            for (Skill skill : skills) {
                List<Tool> allowed = tools.stream()
                        .filter(t -> skill.getAllowedTools().contains(t.getName()))
                        .toList();
                skill.injectParentTools(allowed);
                tools.add(skill.asTool());
            }

            // Sub-agents: inject LLM / factory / parent tools, then register as tool
            for (SubAgent subAgent : subAgents) {
                if (subAgent.isInheritModel()) {
                    subAgent.injectLlm(llm);
                } else if (llmFactory != null) {
                    subAgent.injectLlmFactory(llmFactory);
                }
                if (!subAgent.getAllowedTools().isEmpty()) {
                    List<Tool> allowed = tools.stream()
                            .filter(t -> subAgent.getAllowedTools().contains(t.getName()))
                            .toList();
                    subAgent.injectParentTools(allowed);
                }
                tools.add(subAgent.asTool());
            }

            // tools may be empty when the planner decides no capability is needed;
            // the LLM will answer directly without tool calls in that case.

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
            Consumer<AgentTokenUsageEvent> tokenUsageConsumer = this.tokenUsageConsumer;

            // First node: create per-invocation AgentTaskContext and put in ContextBus
            TranslateHandler<Object, Object> initContext = new TranslateHandler<>(input -> {
                String question = (input instanceof Map<?, ?> m && m.get("input") != null)
                        ? m.get("input").toString()
                        : input.toString();
                // Use pre-loaded context if provided (resume scenario), otherwise create fresh
                AgentTaskContext ctx = ContextBus.get().getTransmit(CallInfo.PRELOADED_CTX.name());
                if (ctx == null) {
                    ctx = contextFinal.create(question, systemPromptFinal);
                } else {
                    // On resume the caller passes the user's follow-up input (e.g. "y" / "n").
                    // Append it as a new human turn so it lands after the completed steps in the
                    // message list, preserving chronological order.
                    ctx.addHumanTurn(question);
                }
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

            TranslateHandler<AIMessage, AIMessage> recordLlmUsage =
                    new TranslateHandler<>(message -> recordLlmUsage(message, tokenUsageConsumer));

            // ── 3. If resuming with prior steps, replace the plain prompt output so the first
            //       LLM call sees the full accumulated context (human + prior tool calls + results)
            TranslateHandler<Object, Object> applyPreloadedSteps = new TranslateHandler<>(promptValue -> {
                AgentTaskContext resumeCtx = ContextBus.get().getTransmit(CallInfo.AGENT_TASK_CTX.name());
                if (resumeCtx != null && !resumeCtx.getCompletedSteps().isEmpty()) {
                    return resumeCtx.buildChatPromptValue();
                }
                return promptValue;
            });

            // ── 4. Loop condition: continue while LLM returns tool calls ──
            int maxIter = this.maxIterations;
            long maxDurationMs = this.maxDurationSeconds > 0 ? (long) this.maxDurationSeconds * 1000 : 0;
            int maxConsecFail = this.maxConsecutiveToolFailures;
            int toolRetryCount = this.toolRetry;
            AtomicLong startTimeMs = new AtomicLong(0);
            AtomicInteger consecutiveFailures = new AtomicInteger(0);

            Function<Integer, Boolean> shouldContinue = i -> {
                AtomicBoolean signal = ContextBus.get().getTransmit(CallInfo.STOP_SIGNAL.name());
                if (signal != null && signal.get()) {
                    AgentTaskContext ctx = ContextBus.get().getTransmit(CallInfo.AGENT_TASK_CTX.name());
                    throw new AgentStoppedException("Agent stopped by external request", ctx);
                }
                if (i == 0) {
                    startTimeMs.set(System.currentTimeMillis());
                    consecutiveFailures.set(0);
                    return true;
                }
                if (maxDurationMs > 0 && System.currentTimeMillis() - startTimeMs.get() > maxDurationMs) {
                    AgentTaskContext ctx = ContextBus.get().getTransmit(CallInfo.AGENT_TASK_CTX.name());
                    throw new AgentAbortException(AgentAbortReason.TIMEOUT,
                            "Agent timed out after " + (maxDurationMs / 1000) + "s", ctx);
                }
                if (i >= maxIter) return false;
                AIMessage aiMessage = ContextBus.get().getResult(llm.getNodeId());
                if (aiMessage instanceof ToolMessage toolMessage) {
                    return !CollectionUtils.isEmpty(toolMessage.getToolCalls());
                }
                return false;
            };

            // ── 5. Tool executor ──
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
                if (ctx != null) ctx.addToolCalls(toolMessage.getToolCalls().size());

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
                        if (maxConsecFail > 0 && consecutiveFailures.incrementAndGet() >= maxConsecFail) {
                            throw new AgentAbortException(AgentAbortReason.CONSECUTIVE_TOOL_FAILURES,
                                    "Consecutive tool failures reached " + maxConsecFail, ctx);
                        }
                    } else {
                        // ── Framework-level auto-retry (transparent to LLM) ──
                        @SuppressWarnings("unchecked")
                        Map<String, Object> argsMap = JsonUtil.fromJson(argsJson, Map.class);
                        Map<String, Object> args = argsMap != null ? argsMap : Map.of();
                        String toolObservation = null;
                        Exception lastError = null;
                        int attempts = 0;
                        while (attempts <= toolRetryCount) {
                            try {
                                Object raw = tool.getFunc().apply(args);
                                toolObservation = raw != null ? raw.toString() : "";
                                lastError = null;
                                break;
                            } catch (AgentPauseException e) {
                                // Record the paused call so the LLM sees full history on resume.
                                // partialContext references the live ctx object, so the step we add
                                // here is visible through e.getPartialContext().getCompletedSteps().
                                toolResults.add(BaseMessage.fromMessage(MessageType.TOOL.getCode(),
                                        "[paused: " + e.getReason() + "]", toolName, toolCall.getId()));
                                ctx.addStep(AgentStep.ofFunctionCall(toolMessage, toolResults));
                                throw e;
                            } catch (AgentException e) {
                                log.debug("Tool '{}' raised agent signal: {}", toolName, e.getMessage());
                                throw e;
                            } catch (Exception e) {
                                lastError = e;
                                attempts++;
                                if (attempts <= toolRetryCount) {
                                    log.warn("Tool '{}' failed (attempt {}/{}), retrying: {}",
                                            toolName, attempts, toolRetryCount + 1, e.getMessage());
                                } else {
                                    log.error("Tool '{}' failed after {} attempt(s)", toolName, attempts, e);
                                }
                            }
                        }
                        if (lastError != null) {
                            observation = "Tool execution error: " + lastError.getMessage();
                            if (maxConsecFail > 0 && consecutiveFailures.incrementAndGet() >= maxConsecFail) {
                                throw new AgentAbortException(AgentAbortReason.CONSECUTIVE_TOOL_FAILURES,
                                        "Consecutive tool failures reached " + maxConsecFail, ctx);
                            }
                        } else {
                            observation = toolObservation;
                            consecutiveFailures.set(0);
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

            // ── 6. Assemble the chain ──
            var chainBuilder = chainActor.builder()
                .next(initContext)
                .next(prompt)
                .next(applyPreloadedSteps)
                .loop(
                    shouldContinue,
                    emitBeforeLlm,
                    llm,
                    chainActor.builder()
                        .next(recordLlmUsage)
                        .next(
                            Info.c(isToolCall, executeTool),
                            Info.c(msg -> ContextBus.get().getResult(llm.getNodeId()))
                        )
                        .build()
                )
                .next(
                    Info.c(output -> output instanceof ChatPromptValue, output -> {
                        AgentTaskContext ctx = ContextBus.get().getTransmit(CallInfo.AGENT_TASK_CTX.name());
                        throw new AgentAbortException(AgentAbortReason.MAX_STEPS,
                                "Max iterations (" + maxIter + ") reached without a final answer.", ctx);
                    }),
                    Info.c(new StrOutputParser())
                )
                .next(new TranslateHandler<>(output -> {
                    if (output instanceof ChatGeneration generation) {
                        attachAggregateUsage(generation);
                    }
                    return output;
                }));

            FlowInstance agentChain = conversationStorerFinal != null
                    ? chainBuilder.next(conversationStorerFinal).build()
                    : chainBuilder.build();

            return new McpAgentExecutor(chainActor, agentChain, conversationStorerFinal);
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
                    if (message instanceof ToolMessage tm && !CollectionUtils.isEmpty(tm.getToolCalls())) {
                        String calls = tm.getToolCalls().stream()
                            .map(tc -> tc.getFunction() != null
                                ? tc.getFunction().getName() + " " + tc.getFunction().getArguments()
                                : tc.getId())
                            .collect(Collectors.joining(", "));
                        return "assistant(tool_calls): " + calls;
                    }
                    if (message instanceof ToolMessage tm && tm.getName() != null) {
                        String content = tm.getContent() != null ? tm.getContent() : "";
                        return "tool[" + tm.getName() + "]: " + content;
                    }
                    String role = message.getRole() != null ? message.getRole() : "unknown";
                    String content = message.getContent() != null ? message.getContent() : "";
                    return role + ": " + content;
                })
                .collect(Collectors.joining("\n"));
        }

        @SuppressWarnings("unchecked")
        private static AIMessage recordLlmUsage(AIMessage message,
                                                Consumer<AgentTokenUsageEvent> tokenUsageConsumer) {
            AgentTaskContext ctx = ContextBus.get().getTransmit(CallInfo.AGENT_TASK_CTX.name());
            if (ctx == null) return message;
            AiTokenUsage usage = null;
            if (message != null && message.getResponseMetadata() != null) {
                Object raw = message.getResponseMetadata().get(AiTokenUsage.METADATA_KEY);
                if (raw instanceof AiTokenUsage tokenUsage) {
                    usage = tokenUsage.copy();
                } else if (raw instanceof Map<?, ?> map) {
                    usage = fromUsageMap((Map<String, Object>) map);
                }
            }
            if (usage == null) usage = AiTokenUsage.empty();
            usage.incrementLlmCalls();
            ctx.addTokenUsage(usage);
            if (tokenUsageConsumer != null) {
                AiTokenUsage total = ctx.getTokenUsage();
                tokenUsageConsumer.accept(AgentTokenUsageEvent.builder()
                        .taskId(ctx.getTaskId())
                        .deltaUsage(usage.copy())
                        .totalUsage(total)
                        .llmCalls(total.getLlmCalls())
                        .toolCalls(total.getToolCalls())
                        .build());
            }
            return message;
        }

        private static void attachAggregateUsage(ChatGeneration generation) {
            AgentTaskContext ctx = ContextBus.get().getTransmit(CallInfo.AGENT_TASK_CTX.name());
            if (ctx == null) return;
            Map<String, Object> metadata = generation.getResponseMetadata() != null
                    ? new HashMap<>(generation.getResponseMetadata())
                    : new HashMap<>();
            metadata.put(AiTokenUsage.METADATA_KEY, ctx.getTokenUsage());
            generation.setResponseMetadata(metadata);
            if (generation.getMessage() != null) {
                generation.getMessage().setResponseMetadata(metadata);
            }
        }

        private static AiTokenUsage fromUsageMap(Map<String, Object> map) {
            AiTokenUsage usage = AiTokenUsage.empty();
            usage.setPromptTokens(asLong(map.get("promptTokens")));
            usage.setCompletionTokens(asLong(map.get("completionTokens")));
            usage.setTotalTokens(asLong(map.get("totalTokens")));
            usage.setCachedTokens(asLong(map.get("cachedTokens")));
            usage.setReasoningTokens(asLong(map.get("reasoningTokens")));
            usage.setLlmCalls(asLong(map.get("llmCalls")));
            usage.setToolCalls(asLong(map.get("toolCalls")));
            Object provider = map.get("provider");
            Object model = map.get("model");
            Object estimated = map.get("estimated");
            if (provider != null) usage.setProvider(provider.toString());
            if (model != null) usage.setModel(model.toString());
            if (estimated instanceof Boolean b) usage.setEstimated(b);
            return usage;
        }

        private static long asLong(Object value) {
            if (value instanceof Number n) return n.longValue();
            if (value == null) return 0;
            try {
                return Long.parseLong(value.toString());
            } catch (NumberFormatException e) {
                return 0;
            }
        }
    }
}
