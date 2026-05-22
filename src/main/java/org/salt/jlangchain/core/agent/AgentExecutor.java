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
import org.apache.commons.lang3.StringUtils;
import org.salt.function.flow.FlowInstance;
import org.salt.function.flow.Info;
import org.salt.function.flow.context.ContextBus;
import org.salt.jlangchain.ai.common.param.AiTokenUsage;
import org.salt.jlangchain.core.BaseRunnable;
import org.salt.jlangchain.core.ChainActor;
import org.salt.jlangchain.core.agent.memory.*;
import org.salt.jlangchain.core.common.CallInfo;
import org.salt.jlangchain.core.handler.TranslateHandler;
import org.salt.jlangchain.core.history.memory.ConversationMemoryStorerBase;
import org.salt.jlangchain.core.llm.BaseChatModel;
import org.salt.jlangchain.core.message.AIMessage;
import org.salt.jlangchain.core.parser.StrOutputParser;
import org.salt.jlangchain.core.parser.generation.ChatGeneration;
import org.salt.jlangchain.core.prompt.string.PromptTemplate;
import org.salt.jlangchain.core.prompt.value.StringPromptValue;
import org.salt.jlangchain.rag.tools.Tool;
import org.salt.jlangchain.rag.tools.annotation.ToolScanner;
import org.salt.jlangchain.utils.PromptUtil;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * ReAct Agent executor.
 *
 * <p>Encapsulates the full Thought → Action → Observation → Final Answer loop.
 *
 * <p>Context management is delegated to {@link AgentTaskContext}. Inject a
 * {@link SlidingWindowContext} for sliding-window compression, or omit {@code context()}
 * to use the default {@link FullContext} (no compression).
 *
 * <pre>{@code
 * ChatGeneration result = AgentExecutor.builder(chainActor)
 *     .llm(ChatOllama.builder().model("llama3:8b").temperature(0f).build())
 *     .tools(getWeather, getTime)
 *     .context(SlidingWindowContext.builder().windowSize(5).build())
 *     .build()
 *     .invoke("What's the weather like in Shanghai now?");
 * }</pre>
 */
@Slf4j
public class AgentExecutor extends BaseRunnable<ChatGeneration, Object> {

    private static final String DEFAULT_REACT_TEMPLATE =
        """
        Answer the following question as best you can. You have access to the following tools:

        ${tools}

        Use the following format:

        Question: the input question you must answer
        Thought: think about whether you have enough information to answer
        Action: the action to take, must be one of [${toolNames}]
        Action Input: the input to the action
        Observation: the result of the action

        (Repeat Thought/Action/Action Input/Observation up to ${maxIterations} times if needed)

        Thought: I now know the final answer
        Final Answer: the final answer to the original input question

        Begin!

        Question: ${input}
        Thought:
        """;

    private final FlowInstance agentChain;
    private final ChainActor chainActor;
    private volatile AtomicBoolean stopSignal = new AtomicBoolean(false);
    private final ConversationMemoryStorerBase conversationStorer;

    private AgentExecutor(ChainActor chainActor, FlowInstance agentChain,
                           ConversationMemoryStorerBase conversationStorer) {
        this.chainActor = chainActor;
        this.agentChain = agentChain;
        this.conversationStorer = conversationStorer;
    }

    /** Stop the currently running invocation. Safe to call from any thread. */
    public void stop() {
        stopSignal.set(true);
    }

    // ── invoke overloads ─────────────────────────────────────────────────────

    @Override
    public ChatGeneration invoke(Object input) {
        return invoke(input.toString(), null, null);
    }

    public ChatGeneration invoke(String input) {
        return invoke(input, null, null);
    }

    public ChatGeneration invoke(String input, AtomicBoolean externalSignal) {
        return invoke(input, externalSignal, null);
    }

    public ChatGeneration invoke(String input, AgentTaskContext preloadedCtx) {
        return invoke(input, null, preloadedCtx);
    }

    public ChatGeneration invoke(String input, AtomicBoolean externalSignal, AgentTaskContext preloadedCtx) {
        this.stopSignal = externalSignal != null ? externalSignal : new AtomicBoolean(false);
        Map<String, Object> transmitMap = new HashMap<>();
        transmitMap.put(CallInfo.STOP_SIGNAL.name(), stopSignal);
        if (preloadedCtx != null) transmitMap.put(CallInfo.PRELOADED_CTX.name(), preloadedCtx);
        try {
            return (ChatGeneration) chainActor.invoke(agentChain, Map.of("input", input), transmitMap);
        } catch (AgentStoppedException e) {
            if (e.getPartialContext() != null) e.getPartialContext().markEnded();
            if (conversationStorer != null) conversationStorer.savePartial(e.getPartialContext());
            throw e;
        } catch (AgentPauseException e) {
            if (e.getPartialContext() != null) e.getPartialContext().markEnded();
            if (conversationStorer != null) conversationStorer.savePartial(e.getPartialContext());
            throw e;
        } catch (AgentAbortException e) {
            if (e.getPartialContext() != null) e.getPartialContext().markEnded();
            if (conversationStorer != null) conversationStorer.savePartial(e.getPartialContext());
            throw e;
        }
    }

    public static Builder builder(ChainActor chainActor) {
        return new Builder(chainActor);
    }

    public static class Builder {

        private static final String LLM_STARTED_AT = "AGENT_EXECUTOR_LLM_STARTED_AT";

        private final ChainActor chainActor;
        private BaseChatModel llm;
        private List<Tool> tools = new ArrayList<>();
        private int maxIterations = 10;
        private int maxDurationSeconds = 0;
        private int maxConsecutiveToolFailures = 0;
        private int toolRetry = 0;
        private AgentContext context;
        private ConversationMemoryStorerBase conversationStorer;
        private String promptTemplate;
        private Consumer<String> llmConsumer;
        private Consumer<String> thoughtConsumer;
        private Consumer<String> observationConsumer;
        private Consumer<AgentTokenUsageEvent> tokenUsageConsumer;

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

        public Builder maxIterations(int maxIterations) {
            this.maxIterations = maxIterations;
            return this;
        }

        /** Maximum wall-clock time for the entire loop. 0 means no limit. */
        public Builder maxDurationSeconds(int maxDurationSeconds) {
            this.maxDurationSeconds = maxDurationSeconds;
            return this;
        }

        /** Abort after this many consecutive failing tool rounds. 0 means no limit. */
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

        public Builder promptTemplate(String promptTemplate) {
            this.promptTemplate = promptTemplate;
            return this;
        }

        public Builder onLlm(Consumer<String> consumer) {
            this.llmConsumer = consumer;
            return this;
        }

        public Builder onThought(Consumer<String> consumer) {
            this.thoughtConsumer = consumer;
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
                this.thoughtConsumer     = t   -> System.out.print("[Thought] " + t);
                this.observationConsumer = obs -> System.out.println("[Observation] " + obs);
            } else {
                this.llmConsumer = null;
                this.thoughtConsumer = null;
                this.observationConsumer = null;
            }
            return this;
        }

        public AgentExecutor build() {
            if (llm == null) throw new IllegalStateException("llm must be set");
            if (tools == null || tools.isEmpty()) throw new IllegalStateException("at least one tool must be provided");

            String template = (promptTemplate != null ? promptTemplate : DEFAULT_REACT_TEMPLATE)
                .replace("${maxIterations}", String.valueOf(maxIterations));

            PromptTemplate prompt = PromptTemplate.fromTemplate(template);
            prompt.withTools(tools);

            AgentContext contextFinal = this.context != null ? this.context : FullContext.build();
            ConversationMemoryStorerBase conversationStorerFinal = this.conversationStorer;
            Consumer<String> llmConsumer = this.llmConsumer;
            Consumer<AgentTokenUsageEvent> tokenUsageConsumer = this.tokenUsageConsumer;

            // First node: create per-invocation AgentTaskContext and put in ContextBus
            TranslateHandler<Object, Object> initContext = new TranslateHandler<>(input -> {
                String question = (input instanceof Map<?, ?> m && m.get("input") != null)
                        ? m.get("input").toString()
                        : input.toString();
                AgentTaskContext ctx = ContextBus.get().getTransmit(CallInfo.PRELOADED_CTX.name());
                if (ctx == null) {
                    ctx = contextFinal.create(question, null);
                } else {
                    ctx.addHumanTurn(question);
                }
                ContextBus.get().putTransmit(CallInfo.AGENT_TASK_CTX.name(), ctx);
                ContextBus.get().putTransmit(CallInfo.QUESTION.name(), question);
                return input;
            });

            TranslateHandler<StringPromptValue, StringPromptValue> emitBeforeLlm = new TranslateHandler<>(promptValue -> {
                ContextBus.get().putTransmit(LLM_STARTED_AT, System.currentTimeMillis());
                if (llmConsumer != null && promptValue != null) {
                    llmConsumer.accept(promptValue.getText());
                }
                return promptValue;
            });

            TranslateHandler<AIMessage, AIMessage> recordLlmUsage =
                    new TranslateHandler<>(message -> recordLlmUsage(message, tokenUsageConsumer));

            Consumer<String> thoughtConsumer = this.thoughtConsumer;
            TranslateHandler<AIMessage, AIMessage> cutAtObservation = new TranslateHandler<>(llmResult -> {
                if (llmResult == null || StringUtils.isEmpty(llmResult.getContent())) {
                    return llmResult;
                }
                String content = llmResult.getContent();
                String prefix = content.contains("Observation:")
                    ? content.substring(0, content.indexOf("Observation:"))
                    : content;
                if (thoughtConsumer != null) thoughtConsumer.accept(prefix);
                else log.debug("Thought/Action:\n{}", prefix);
                llmResult.setContent(prefix);
                return llmResult;
            });

            TranslateHandler<Map<String, String>, AIMessage> parseAction =
                new TranslateHandler<>(llmResult -> PromptUtil.stringToMap(llmResult.getContent()));

            TranslateHandler<StringPromptValue, StringPromptValue> applyPreloadedSteps = new TranslateHandler<>(promptValue -> {
                AgentTaskContext resumeCtx = ContextBus.get().getTransmit(CallInfo.AGENT_TASK_CTX.name());
                String question = ContextBus.get().getTransmit(CallInfo.QUESTION.name());
                if (resumeCtx != null && !resumeCtx.getCompletedSteps().isEmpty()) {
                    resumeCtx.initReactBasePromptText(promptValue.getText());
                    String text = resumeCtx.buildReactPromptText();
                    if (StringUtils.isNotBlank(question)) {
                        text = text.trim() + "\nHuman: " + question + "\nThought:";
                    }
                    promptValue.setText(text);
                }
                return promptValue;
            });

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
                Map<String, String> parsed = ContextBus.get().getResult(parseAction.getNodeId());
                boolean actionRequested = parsed == null
                        || (parsed.containsKey("Action") && parsed.containsKey("Action Input"));
                if (i >= maxIter && actionRequested) {
                    AgentTaskContext ctx = ContextBus.get().getTransmit(CallInfo.AGENT_TASK_CTX.name());
                    throw new AgentAbortException(AgentAbortReason.MAX_STEPS,
                            "Max iterations (" + maxIter + ") reached without a final answer.", ctx);
                }
                return actionRequested;
            };

            Function<Object, Boolean> needsToolCall = map ->
                ((Map<String, String>) map).containsKey("Action") && ((Map<String, String>) map).containsKey("Action Input");

            List<Tool> toolList = this.tools;
            Consumer<String> observationConsumer = this.observationConsumer;

            TranslateHandler<Object, Map<String, String>> executeTool = new TranslateHandler<>(map -> {
                StringPromptValue promptValue = ContextBus.get().getResult(prompt.getNodeId());
                AIMessage cutResult = ContextBus.get().getResult(cutAtObservation.getNodeId());
                AgentTaskContext ctx = ContextBus.get().getTransmit(CallInfo.AGENT_TASK_CTX.name());
                if (ctx != null) ctx.addToolCalls(1);

                Tool useTool = toolList.stream()
                    .filter(t -> t.getName().equalsIgnoreCase(((Map<String, String>) map).get("Action")))
                    .findFirst().orElse(null);

                String observation;
                if (useTool == null) {
                    log.warn("Unknown tool requested: {}", ((Map<String, String>) map).get("Action"));
                    observation = "Tool '" + ((Map<String, String>) map).get("Action") + "' not found.";
                    if (maxConsecFail > 0 && consecutiveFailures.incrementAndGet() >= maxConsecFail) {
                        addReactStep(ctx, promptValue, cutResult, observation);
                        throw new AgentAbortException(AgentAbortReason.CONSECUTIVE_TOOL_FAILURES,
                                "Consecutive tool failures reached " + maxConsecFail, ctx);
                    }
                } else {
                    String toolObservation = null;
                    Exception lastError = null;
                    int attempts = 0;
                    long toolStartedAt = System.currentTimeMillis();
                    while (attempts <= toolRetryCount) {
                        try {
                            Object raw = useTool.getFunc().apply(((Map<String, String>) map).get("Action Input"));
                            toolObservation = raw != null ? raw.toString() : "";
                            lastError = null;
                            break;
                        } catch (AgentPauseException e) {
                            ctx.addToolDuration(System.currentTimeMillis() - toolStartedAt);
                            addReactStep(ctx, promptValue, cutResult, "[paused: " + e.getReason() + "]");
                            throw e;
                        } catch (AgentException e) {
                            ctx.addToolDuration(System.currentTimeMillis() - toolStartedAt);
                            log.debug("Tool '{}' raised agent signal: {}", useTool.getName(), e.getMessage());
                            throw e;
                        } catch (Exception e) {
                            lastError = e;
                            attempts++;
                            if (attempts <= toolRetryCount) {
                                log.warn("Tool '{}' failed (attempt {}/{}), retrying: {}",
                                        useTool.getName(), attempts, toolRetryCount + 1, e.getMessage());
                            } else {
                                log.error("Tool '{}' failed after {} attempt(s)", useTool.getName(), attempts, e);
                            }
                        }
                    }
                    ctx.addToolDuration(System.currentTimeMillis() - toolStartedAt);
                    if (lastError != null) {
                        observation = "Tool execution error: " + lastError.getMessage();
                        if (maxConsecFail > 0 && consecutiveFailures.incrementAndGet() >= maxConsecFail) {
                            addReactStep(ctx, promptValue, cutResult, observation);
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

                addReactStep(ctx, promptValue, cutResult, observation);
                return promptValue;
            });

            TranslateHandler<Object, Object> extractFinalAnswer = new TranslateHandler<>(input -> {
                ChatGeneration generation = (ChatGeneration) input;
                String content = generation.getText();
                if (content.contains("Final Answer:")) {
                    int start = content.indexOf("Final Answer:") + 13;
                    generation.setText(content.substring(start).trim());
                }
                return generation;
            });

            TranslateHandler<Object, Object> attachTokenUsage = new TranslateHandler<>(input -> {
                if (input instanceof ChatGeneration generation) {
                    attachAggregateUsage(generation);
                }
                return input;
            });

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
                        .next(cutAtObservation)
                        .next(parseAction)
                        .next(
                            Info.c(needsToolCall, executeTool),
                            Info.c(input -> ContextBus.get().getResult(llm.getNodeId()))
                        )
                        .build()
                )
                .next(new StrOutputParser())
                .next(attachTokenUsage)
                .next(extractFinalAnswer);

            FlowInstance agentChain = conversationStorerFinal != null
                    ? chainBuilder.next(conversationStorerFinal).build()
                    : chainBuilder.build();

            return new AgentExecutor(chainActor, agentChain, conversationStorerFinal);
        }

        private static void addReactStep(AgentTaskContext ctx, StringPromptValue promptValue,
                                         AIMessage cutResult, String observation) {
            String prefix = cutResult != null && cutResult.getContent() != null ? cutResult.getContent() : "";
            int thoughtIdx = prefix.lastIndexOf("Thought:");
            String thoughtPart = thoughtIdx >= 0 ? prefix.substring(thoughtIdx + 8).trim() : prefix.trim();
            String agentScratchpad = thoughtPart + "\nObservation: " + observation + "\nThought:";

            ctx.initReactBasePromptText(promptValue.getText());
            ctx.addStep(AgentStep.ofReAct(agentScratchpad));
            promptValue.setText(ctx.buildReactPromptText());
        }

        @SuppressWarnings("unchecked")
        private static AIMessage recordLlmUsage(AIMessage message,
                                                Consumer<AgentTokenUsageEvent> tokenUsageConsumer) {
            AgentTaskContext ctx = ContextBus.get().getTransmit(CallInfo.AGENT_TASK_CTX.name());
            if (ctx == null) return message;
            long deltaDurationMs = 0;
            Long startedAt = ContextBus.get().getTransmit(LLM_STARTED_AT);
            if (startedAt != null) {
                deltaDurationMs = Math.max(0, System.currentTimeMillis() - startedAt);
                ctx.addLlmDuration(deltaDurationMs);
            }
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
                AgentExecutionMetrics metrics = ctx.getExecutionMetrics();
                tokenUsageConsumer.accept(AgentTokenUsageEvent.builder()
                        .taskId(ctx.getTaskId())
                        .deltaUsage(usage.copy())
                        .totalUsage(total)
                        .llmCalls(total.getLlmCalls())
                        .toolCalls(total.getToolCalls())
                        .deltaDurationMs(deltaDurationMs)
                        .totalDurationMs(metrics.getDurationMs())
                        .llmDurationMs(metrics.getLlmDurationMs())
                        .toolDurationMs(metrics.getToolDurationMs())
                        .build());
            }
            return message;
        }

        private static void attachAggregateUsage(ChatGeneration generation) {
            AgentTaskContext ctx = ContextBus.get().getTransmit(CallInfo.AGENT_TASK_CTX.name());
            if (ctx == null) return;
            ctx.markEnded();
            Map<String, Object> metadata = generation.getResponseMetadata() != null
                    ? new HashMap<>(generation.getResponseMetadata())
                    : new HashMap<>();
            metadata.put(AiTokenUsage.METADATA_KEY, ctx.getTokenUsage());
            metadata.put(AgentExecutionMetrics.METADATA_KEY, ctx.getExecutionMetrics());
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
