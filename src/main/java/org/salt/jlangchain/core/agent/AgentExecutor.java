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
import org.salt.jlangchain.core.handler.TranslateHandler;
import org.salt.jlangchain.core.history.HistoryInfos;
import org.salt.jlangchain.core.history.storage.AgentTaskStorage;
import org.salt.jlangchain.core.history.storage.ConversationStorage;
import org.salt.jlangchain.core.llm.BaseChatModel;
import org.salt.jlangchain.core.message.AIMessage;
import org.salt.jlangchain.core.message.HumanMessage;
import org.salt.jlangchain.core.message.BaseMessage;
import org.salt.jlangchain.core.parser.StrOutputParser;
import org.salt.jlangchain.core.parser.generation.ChatGeneration;
import org.salt.jlangchain.core.prompt.string.PromptTemplate;
import org.salt.jlangchain.core.prompt.value.StringPromptValue;
import org.salt.jlangchain.rag.tools.Tool;
import org.salt.jlangchain.rag.tools.annotation.ToolScanner;
import org.salt.jlangchain.utils.PromptUtil;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * ReAct Agent executor.
 *
 * <p>Encapsulates the full Thought → Action → Observation → Final Answer loop.
 *
 * <p>AgentTaskContext manages a sliding window of recent steps. When the window exceeds
 * {@code windowSize}, the oldest step is compressed into {@code earlyStepsSummary} and
 * injected into the reconstructed prompt, preventing unbounded text growth over long loops.
 *
 * <pre>{@code
 * ChatGeneration result = AgentExecutor.builder(chainActor)
 *     .llm(ChatOllama.builder().model("llama3:8b").temperature(0f).build())
 *     .tools(getWeather, getTime)
 *     .windowSize(5)
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

    /** Carries the per-invocation AgentTaskContext into the lambda handlers. */
    private final ThreadLocal<AgentTaskContext> contextHolder;

    private final int windowSize;
    private final BaseChatModel summarizer;
    private final AgentTaskStorage agentTaskStorage;
    private final ConversationStorage conversationStorage;
    private final Long appId;
    private final Long userId;
    private final Long sessionId;
    private final String parentId;

    private AgentExecutor(ChainActor chainActor, FlowInstance agentChain,
                          ThreadLocal<AgentTaskContext> contextHolder,
                          int windowSize, BaseChatModel summarizer,
                          AgentTaskStorage agentTaskStorage,
                          ConversationStorage conversationStorage,
                          Long appId, Long userId, Long sessionId, String parentId) {
        this.chainActor = chainActor;
        this.agentChain = agentChain;
        this.contextHolder = contextHolder;
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
     * Execute the agent with the given user input (node-compatible entry point).
     */
    @Override
    public ChatGeneration invoke(Object input) {
        String question = input.toString();
        AgentTaskContext ctx = AgentTaskContext.create(question, null, windowSize, parentId);
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
     * Execute the agent with the given user input.
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
        private int maxIterations = 10;
        private int windowSize = Integer.MAX_VALUE;
        private BaseChatModel summarizer;
        private AgentTaskStorage agentTaskStorage;
        private ConversationStorage conversationStorage;
        private Long appId;
        private Long userId;
        private Long sessionId;
        private String parentId;
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

        /** Scan {@code toolsProvider} for {@link org.salt.jlangchain.rag.tools.annotation.AgentTool}-annotated methods. */
        public Builder tools(Object toolsProvider) {
            this.tools.addAll(ToolScanner.scan(toolsProvider));
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

        /** Optional parent record id for tree-structured history. */
        public Builder parentId(String parentId) {
            this.parentId = parentId;
            return this;
        }

        /** Override the default ReAct prompt template. Must contain ${tools}, ${toolNames}, ${maxIterations}, ${input}. */
        public Builder promptTemplate(String promptTemplate) {
            this.promptTemplate = promptTemplate;
            return this;
        }

        /** Hook called with the full LLM input text before each model invocation. */
        public Builder onLlm(Consumer<String> consumer) {
            this.llmConsumer = consumer;
            return this;
        }

        /** Hook called with each Thought+Action block before tool execution. */
        public Builder onThought(Consumer<String> consumer) {
            this.thoughtConsumer = consumer;
            return this;
        }

        /** Hook called with each Observation result after tool execution. */
        public Builder onObservation(Consumer<String> consumer) {
            this.observationConsumer = consumer;
            return this;
        }

        public AgentExecutor build() {
            if (llm == null) throw new IllegalStateException("llm must be set");
            if (tools == null || tools.isEmpty()) throw new IllegalStateException("at least one tool must be provided");

            String template = (promptTemplate != null ? promptTemplate : DEFAULT_REACT_TEMPLATE)
                .replace("${maxIterations}", String.valueOf(maxIterations));

            PromptTemplate prompt = PromptTemplate.fromTemplate(template);
            prompt.withTools(tools);

            Consumer<String> llmConsumer = this.llmConsumer;

            TranslateHandler<StringPromptValue, StringPromptValue> emitBeforeLlm = new TranslateHandler<>(promptValue -> {
                if (llmConsumer != null && promptValue != null) {
                    llmConsumer.accept(promptValue.getText());
                }
                return promptValue;
            });

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
                Map<String, String> parsed = ContextBus.get().getResult(parseAction.getNodeId());
                return i < maxIter
                    && (parsed == null || (parsed.containsKey("Action") && parsed.containsKey("Action Input")));
            };

            Function<Object, Boolean> needsToolCall = map ->
                ((Map<String, String>) map).containsKey("Action") && ((Map<String, String>) map).containsKey("Action Input");

            List<Tool> toolList = this.tools;
            Consumer<String> observationConsumer = this.observationConsumer;
            BaseChatModel summarizerFinal = this.summarizer;
            AgentTaskStorage agentTaskStorageFinal = this.agentTaskStorage;

            // Shared context holder: created here, set/cleared in invoke() per call.
            ThreadLocal<AgentTaskContext> contextHolder = new ThreadLocal<>();

            TranslateHandler<Object, Map<String, String>> executeTool = new TranslateHandler<>(map -> {
                StringPromptValue promptValue = ContextBus.get().getResult(prompt.getNodeId());
                AIMessage cutResult = ContextBus.get().getResult(cutAtObservation.getNodeId());
                AgentTaskContext ctx = contextHolder.get();

                Tool useTool = toolList.stream()
                    .filter(t -> t.getName().equalsIgnoreCase(((Map<String, String>) map).get("Action")))
                    .findFirst().orElse(null);

                if (useTool == null) {
                    log.warn("Unknown tool requested: {}", ((Map<String, String>) map).get("Action"));
                    // Record base before the "tool not found" note so future steps still have the right base
                    if (ctx != null) ctx.initReactBasePromptText(promptValue.getText());
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

                if (ctx != null) {
                    // Save base text on first call (before any mutation of promptValue)
                    ctx.initReactBasePromptText(promptValue.getText());
                    // Record step (handles window + optional storage)
                    ctx.addStep(AgentStep.ofReAct(agentScratchpad), summarizerFinal, agentTaskStorageFinal);
                    // Rebuild the full prompt from base + windowed scratchpad
                    promptValue.setText(ctx.buildReactPromptText());
                    return promptValue;
                }

                // Fallback: original accumulate-in-place behaviour
                promptValue.setText(promptValue.getText().trim() + agentScratchpad);
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

            FlowInstance agentChain = chainActor.builder()
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
                .next(extractFinalAnswer)
                .build();

            return new AgentExecutor(chainActor, agentChain, contextHolder,
                    windowSize, summarizer, agentTaskStorage,
                    conversationStorage, appId, userId, sessionId, parentId);
        }
    }
}
