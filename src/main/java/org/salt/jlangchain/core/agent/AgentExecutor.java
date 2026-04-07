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
import org.salt.jlangchain.core.ChainActor;
import org.salt.jlangchain.core.handler.TranslateHandler;
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
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * ReAct Agent executor.
 *
 * Encapsulates the full Thought → Action → Observation → Final Answer loop.
 * Use {@link #builder(ChainActor)} to construct an instance.
 *
 * <pre>{@code
 * ChatGeneration result = AgentExecutor.builder(chainActor)
 *     .llm(ChatOllama.builder().model("llama3:8b").temperature(0f).build())
 *     .tools(getWeather, getTime)
 *     .build()
 *     .invoke("上海现在的天气怎么样？");
 * }</pre>
 */
@Slf4j
public class AgentExecutor {

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

    private AgentExecutor(ChainActor chainActor, FlowInstance agentChain) {
        this.chainActor = chainActor;
        this.agentChain = agentChain;
    }

    /**
     * Execute the agent with the given user input.
     *
     * @param input the user question
     * @return the final answer
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
        private int maxIterations = 10;
        private String promptTemplate;
        private Consumer<String> thoughtLogger;
        private Consumer<String> observationLogger;

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

        /** Override the default ReAct prompt template. Must contain ${tools}, ${toolNames}, ${maxIterations}, ${input}. */
        public Builder promptTemplate(String promptTemplate) {
            this.promptTemplate = promptTemplate;
            return this;
        }

        /** Hook called with each Thought+Action block before tool execution. */
        public Builder onThought(Consumer<String> logger) {
            this.thoughtLogger = logger;
            return this;
        }

        /** Hook called with each Observation result after tool execution. */
        public Builder onObservation(Consumer<String> logger) {
            this.observationLogger = logger;
            return this;
        }

        public AgentExecutor build() {
            if (llm == null) throw new IllegalStateException("llm must be set");
            if (tools == null || tools.isEmpty()) throw new IllegalStateException("at least one tool must be provided");

            String template = (promptTemplate != null ? promptTemplate : DEFAULT_REACT_TEMPLATE)
                .replace("${maxIterations}", String.valueOf(maxIterations));

            PromptTemplate prompt = PromptTemplate.fromTemplate(template);
            prompt.withTools(tools);

            TranslateHandler<AIMessage, AIMessage> cutAtObservation = new TranslateHandler<>(llmResult -> {
                if (llmResult == null || StringUtils.isEmpty(llmResult.getContent())
                        || !llmResult.getContent().contains("Observation:")) {
                    return llmResult;
                }
                String prefix = llmResult.getContent().substring(0, llmResult.getContent().indexOf("Observation:"));
                if (thoughtLogger != null) thoughtLogger.accept(prefix);
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
            Consumer<String> obsLogger = this.observationLogger;

            TranslateHandler<Object, Map<String, String>> executeTool = new TranslateHandler<>(map -> {
                StringPromptValue promptValue = ContextBus.get().getResult(prompt.getNodeId());
                AIMessage cutResult = ContextBus.get().getResult(cutAtObservation.getNodeId());

                Tool useTool = toolList.stream()
                    .filter(t -> t.getName().equalsIgnoreCase(((Map<String, String>) map).get("Action")))
                    .findFirst().orElse(null);

                if (useTool == null) {
                    log.warn("Unknown tool requested: {}", ((Map<String, String>) map).get("Action"));
                    promptValue.setText(promptValue.getText().trim() + "\nThought: The requested tool does not exist, please choose another.\nThought:");
                    return promptValue;
                }

                String observation = (String) useTool.getFunc().apply(((Map<String, String>) map).get("Action Input"));
                if (obsLogger != null) obsLogger.accept(observation);
                else log.debug("Observation: {}", observation);

                String prefix = cutResult.getContent();
                int thoughtIdx = prefix.lastIndexOf("Thought:");
                String thoughtPart = thoughtIdx >= 0 ? prefix.substring(thoughtIdx + 8).trim() : prefix.trim();
                String agentScratchpad = thoughtPart + "\nObservation: " + observation + "\nThought:";
                promptValue.setText(promptValue.getText().trim() + agentScratchpad);

                return promptValue;
            });

            TranslateHandler<Object, Object> extractFinalAnswer = new TranslateHandler<>(input -> {
                ChatGeneration generation = (ChatGeneration) input;
                String content = generation.getText();
                if (content.contains("Final Answer:")) {
                    int start = content.indexOf("Final Answer:") + 13;
                    int end = content.indexOf("\n", start);
                    generation.setText(end > 0
                        ? content.substring(start, end).trim()
                        : content.substring(start).trim());
                }
                return generation;
            });

            FlowInstance agentChain = chainActor.builder()
                .next(prompt)
                .loop(
                    shouldContinue,
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

            return new AgentExecutor(chainActor, agentChain);
        }
    }
}
