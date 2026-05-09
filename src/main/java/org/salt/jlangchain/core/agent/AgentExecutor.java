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
        private int maxIterations = 10;
        private AgentContext context;
        private ConversationMemoryStorerBase conversationStorer;
        private String promptTemplate;
        private Consumer<String> llmConsumer;
        private Consumer<String> thoughtConsumer;
        private Consumer<String> observationConsumer;

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

            // First node: create per-invocation AgentTaskContext and put in ContextBus
            TranslateHandler<Object, Object> initContext = new TranslateHandler<>(input -> {
                String question = (input instanceof Map<?, ?> m && m.get("input") != null)
                        ? m.get("input").toString()
                        : input.toString();
                AgentTaskContext ctx = ContextBus.get().getTransmit(CallInfo.PRELOADED_CTX.name());
                if (ctx == null) {
                    ctx = contextFinal.create(question, null);
                }
                ContextBus.get().putTransmit(CallInfo.AGENT_TASK_CTX.name(), ctx);
                ContextBus.get().putTransmit(CallInfo.QUESTION.name(), question);
                return input;
            });

            TranslateHandler<StringPromptValue, StringPromptValue> emitBeforeLlm = new TranslateHandler<>(promptValue -> {
                if (llmConsumer != null && promptValue != null) {
                    llmConsumer.accept(promptValue.getText());
                }
                return promptValue;
            });

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

            int maxIter = this.maxIterations;
            Function<Integer, Boolean> shouldContinue = i -> {
                AtomicBoolean signal = ContextBus.get().getTransmit(CallInfo.STOP_SIGNAL.name());
                if (signal != null && signal.get()) {
                    AgentTaskContext ctx = ContextBus.get().getTransmit(CallInfo.AGENT_TASK_CTX.name());
                    throw new AgentStoppedException("Agent stopped by external request", ctx);
                }
                Map<String, String> parsed = ContextBus.get().getResult(parseAction.getNodeId());
                return i < maxIter
                    && (parsed == null || (parsed.containsKey("Action") && parsed.containsKey("Action Input")));
            };

            Function<Object, Boolean> needsToolCall = map ->
                ((Map<String, String>) map).containsKey("Action") && ((Map<String, String>) map).containsKey("Action Input");

            List<Tool> toolList = this.tools;
            Consumer<String> observationConsumer = this.observationConsumer;

            TranslateHandler<Object, Map<String, String>> executeTool = new TranslateHandler<>(map -> {
                StringPromptValue promptValue = ContextBus.get().getResult(prompt.getNodeId());
                AIMessage cutResult = ContextBus.get().getResult(cutAtObservation.getNodeId());
                AgentTaskContext ctx = ContextBus.get().getTransmit(CallInfo.AGENT_TASK_CTX.name());

                Tool useTool = toolList.stream()
                    .filter(t -> t.getName().equalsIgnoreCase(((Map<String, String>) map).get("Action")))
                    .findFirst().orElse(null);

                if (useTool == null) {
                    log.warn("Unknown tool requested: {}", ((Map<String, String>) map).get("Action"));
                    ctx.initReactBasePromptText(promptValue.getText());
                    promptValue.setText(promptValue.getText().trim()
                            + "\nThought: The requested tool does not exist, please choose another.\nThought:");
                    return promptValue;
                }

                String observation = (String) useTool.getFunc().apply(((Map<String, String>) map).get("Action Input"));
                if (observationConsumer != null) observationConsumer.accept(observation);
                else log.debug("Observation: {}", observation);

                String prefix = cutResult.getContent();
                int thoughtIdx = prefix.lastIndexOf("Thought:");
                String thoughtPart = thoughtIdx >= 0 ? prefix.substring(thoughtIdx + 8).trim() : prefix.trim();
                String agentScratchpad = thoughtPart + "\nObservation: " + observation + "\nThought:";

                ctx.initReactBasePromptText(promptValue.getText());
                ctx.addStep(AgentStep.ofReAct(agentScratchpad));
                promptValue.setText(ctx.buildReactPromptText());
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

            var chainBuilder = chainActor.builder()
                .next(initContext)
                .next(prompt)
                .loop(
                    shouldContinue,
                    emitBeforeLlm,
                    llm,
                    chainActor.builder()
                        .next(cutAtObservation)
                        .next(parseAction)
                        .next(
                            Info.c(needsToolCall, executeTool),
                            Info.c(input -> ContextBus.get().getResult(llm.getNodeId()))
                        )
                        .build()
                )
                .next(new StrOutputParser())
                .next(extractFinalAnswer);

            FlowInstance agentChain = conversationStorerFinal != null
                    ? chainBuilder.next(conversationStorerFinal).build()
                    : chainBuilder.build();

            return new AgentExecutor(chainActor, agentChain, conversationStorerFinal);
        }
    }
}
