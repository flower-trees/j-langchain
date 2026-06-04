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

package org.salt.jlangchain.demo.flow;

import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.salt.jlangchain.TestApplication;
import org.salt.jlangchain.ai.common.param.AiTokenUsage;
import org.salt.jlangchain.core.ChainActor;
import org.salt.jlangchain.core.agent.AgentExecutionMetrics;
import org.salt.jlangchain.core.agent.AgentTokenUsageEvent;
import org.salt.jlangchain.core.agent.AgentExecutor;
import org.salt.jlangchain.core.agent.McpAgentExecutor;
import org.salt.jlangchain.core.agent.memory.SlidingWindowContext;
import org.salt.jlangchain.core.agent.storage.AgentTaskStorage;
import org.salt.jlangchain.core.history.HistoryInfos;
import org.salt.jlangchain.core.history.memory.buffer.ConversationBufferMemoryStorer;
import org.salt.jlangchain.core.agent.storage.InMemoryAgentTaskStorage;
import org.salt.jlangchain.core.history.storage.InMemoryConversationStorage;
import org.salt.jlangchain.core.llm.aliyun.ChatAliyun;
import org.salt.jlangchain.core.parser.generation.ChatGeneration;
import org.salt.jlangchain.rag.tools.Tool;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.junit4.SpringRunner;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.stream.Collectors;

/**
 * Tests for AgentTaskContext sliding-window, AgentTaskStorage, and ConversationMemoryStorerBase
 * integration in AgentExecutor and McpAgentExecutor.
 */
@RunWith(SpringRunner.class)
@SpringBootTest(classes = TestApplication.class)
@SpringBootConfiguration
public class ChainAgentContextTest {

    @Autowired
    ChainActor chainActor;

    // ── Shared LLM factory (thinking disabled to avoid agent-loop latency) ─────

    static ChatAliyun qwenAgent() {
        return ChatAliyun.builder()
                .model("qwen3.6-plus")
                .temperature(0f)
                .modelKwargs(Map.of("enable_thinking", false))
                .build();
    }

    // ── Shared mock tools ─────────────────────────────────────────────────────

    static Tool weatherTool() {
        return Tool.builder()
                .name("get_weather")
                .params("city: String")
                .description("Get the current weather for a city. Input is the city name.")
                .func(city -> city + ": sunny, 25°C")
                .build();
    }

    static Tool timeTool() {
        return Tool.builder()
                .name("get_time")
                .params("city: String")
                .description("Get the current local time for a city. Input is the city name.")
                .func(city -> city + ": 14:30")
                .build();
    }

    // ── 1. AgentExecutor — default FullContext (no .context() call) ───────────

    @Test
    public void testAgentExecutorDefaultBehavior() {
        AgentExecutor agent = AgentExecutor.builder(chainActor)
                .llm(qwenAgent())
                .tools(weatherTool(), timeTool())
                .maxIterations(6)
                .onThought(t -> System.out.print("[Thought] " + t))
                .onObservation(obs -> System.out.println("[Observation] " + obs))
                .onTokenUsage(event -> System.out.println("[AgentExecutor tokenUsage event] " + formatUsageEvent(event)))
                .build();

        ChatGeneration result = agent.invoke("上海现在天气怎么样？");
        System.out.println("[Final] " + result.getText());
        Assert.assertNotNull(result);
        Assert.assertFalse(result.getText().isBlank());
        AiTokenUsage usage = tokenUsage(result);
        Assert.assertNotNull("AgentExecutor should expose token usage", usage);
        System.out.println("[AgentExecutor tokenUsage] " + usage);
        AgentExecutionMetrics metrics = executionMetrics(result);
        Assert.assertNotNull("AgentExecutor should expose execution metrics", metrics);
        System.out.println("[AgentExecutor executionMetrics] " + metrics);
    }

    // ── 2. AgentExecutor — SlidingWindowContext ───────────────────────────────

    @Test
    public void testAgentExecutorWithSlidingWindow() {
        AgentExecutor agent = AgentExecutor.builder(chainActor)
                .llm(qwenAgent())
                .tools(weatherTool(), timeTool())
                .maxIterations(8)
                .context(SlidingWindowContext.builder().windowSize(1).build())
                .onThought(t -> System.out.print("[Thought] " + t))
                .onObservation(obs -> System.out.println("[Observation] " + obs))
                .build();

        ChatGeneration result = agent.invoke("请告诉我上海的天气和当前时间各是什么？");
        System.out.println("[Final] " + result.getText());
        Assert.assertNotNull(result);
        Assert.assertFalse(result.getText().isBlank());
        AiTokenUsage usage = tokenUsage(result);
        Assert.assertNotNull("McpAgentExecutor should expose token usage", usage);
        Assert.assertTrue("McpAgentExecutor should record LLM calls", usage.getLlmCalls() >= 1);
        System.out.println("[McpAgentExecutor tokenUsage] " + usage);
        AgentExecutionMetrics metrics = executionMetrics(result);
        Assert.assertNotNull("McpAgentExecutor should expose execution metrics", metrics);
        System.out.println("[McpAgentExecutor executionMetrics] " + metrics);
    }

    // ── 3. AgentExecutor — SlidingWindowContext + AgentTaskStorage ────────────

    @Test
    public void testAgentExecutorWithAgentTaskStorage() {
        InMemoryAgentTaskStorage taskStorage = new InMemoryAgentTaskStorage();

        AgentExecutor agent = AgentExecutor.builder(chainActor)
                .llm(qwenAgent())
                .tools(weatherTool(), timeTool())
                .maxIterations(6)
                .context(SlidingWindowContext.builder().taskStorage(taskStorage).build())
                .onThought(t -> System.out.print("[Thought] " + t))
                .onObservation(obs -> System.out.println("[Observation] " + obs))
                .build();

        ChatGeneration result = agent.invoke("上海天气怎么样？");
        System.out.println("[Final] " + result.getText());
        Assert.assertNotNull(result);
        System.out.println("[AgentTaskStorage] step recording via SlidingWindowContext verified");
    }

    // ── 4. McpAgentExecutor — default FullContext ─────────────────────────────

    @Test
    public void testMcpAgentExecutorDefaultBehavior() {
        McpAgentExecutor agent = McpAgentExecutor.builder(chainActor)
                .llm(qwenAgent())
                .tools(weatherTool(), timeTool())
                .systemPrompt("你是一名智能助手，请使用工具来回答用户问题。")
                .maxIterations(6)
                .onToolCall(tc -> System.out.println("[ToolCall] " + tc))
                .onObservation(obs -> System.out.println("[Observation] " + obs))
                .onTokenUsage(event -> System.out.println("[McpAgentExecutor tokenUsage event] " + formatUsageEvent(event)))
                .build();

        ChatGeneration result = agent.invoke("上海现在天气怎么样？");
        System.out.println("[Final] " + result.getText());
        Assert.assertNotNull(result);
        Assert.assertFalse(result.getText().isBlank());
    }

    // ── 5. McpAgentExecutor — SlidingWindowContext ────────────────────────────

    @Test
    public void testMcpAgentExecutorWithSlidingWindow() {
        McpAgentExecutor agent = McpAgentExecutor.builder(chainActor)
                .llm(qwenAgent())
                .tools(weatherTool(), timeTool())
                .systemPrompt("你是一名智能助手，请使用工具来回答用户问题。")
                .maxIterations(8)
                .context(SlidingWindowContext.builder().windowSize(1).build())
                .onToolCall(tc -> System.out.println("[ToolCall] " + tc))
                .onObservation(obs -> System.out.println("[Observation] " + obs))
                .build();

        ChatGeneration result = agent.invoke("请分别告诉我上海和北京的天气。");
        System.out.println("[Final] " + result.getText());
        Assert.assertNotNull(result);
        Assert.assertFalse(result.getText().isBlank());
    }

    // ── 6. McpAgentExecutor — ConversationBufferMemoryStorer saves final turn ─

    @Test
    public void testMcpAgentExecutorWithConversationStorer() {
        InMemoryConversationStorage conversationStorage = new InMemoryConversationStorage();
        Long appId = 0L, userId = 1L, sessionId = 100L;

        ConversationBufferMemoryStorer storer = ConversationBufferMemoryStorer.builder()
                .storage(conversationStorage)
                .appId(appId)
                .userId(userId)
                .sessionId(sessionId)
                .build();

        McpAgentExecutor agent = McpAgentExecutor.builder(chainActor)
                .llm(qwenAgent())
                .tools(weatherTool())
                .systemPrompt("你是一名智能助手。")
                .maxIterations(6)
                .conversationStorer(storer)
                .onToolCall(tc -> System.out.println("[ToolCall] " + tc))
                .onObservation(obs -> System.out.println("[Observation] " + obs))
                .build();

        ChatGeneration result = agent.invoke("上海天气怎么样？");
        System.out.println("[Final] " + result.getText());
        Assert.assertNotNull(result);

        List<HistoryInfos> history = conversationStorage.loadAll(appId, userId, sessionId);
        System.out.println("[ConversationStorage] saved turns: " + history.size());
        Assert.assertFalse("ConversationStorage should have at least one turn", history.isEmpty());

        HistoryInfos saved = history.get(0);
        Assert.assertEquals(HistoryInfos.Type.NORMAL, saved.getType());
        Assert.assertEquals(2, saved.getMessages().size());
        System.out.println("[Turn 0 human] " + saved.getMessages().get(0).getContent());
        System.out.println("[Turn 0 ai]    " + saved.getMessages().get(1).getContent());
    }

    // ── 7. McpAgentExecutor — full integration ────────────────────────────────

    @Test
    public void testMcpAgentExecutorFullIntegration() {
        InMemoryConversationStorage conversationStorage = new InMemoryConversationStorage();
        InMemoryAgentTaskStorage taskStorage = new InMemoryAgentTaskStorage();
        Long appId = 0L, userId = 2L, sessionId = 200L;

        ConversationBufferMemoryStorer storer = ConversationBufferMemoryStorer.builder()
                .storage(conversationStorage)
                .appId(appId)
                .userId(userId)
                .sessionId(sessionId)
                .build();

        McpAgentExecutor agent = McpAgentExecutor.builder(chainActor)
                .llm(qwenAgent())
                .tools(weatherTool(), timeTool())
                .systemPrompt("你是一名智能助手，请使用工具回答用户问题。")
                .maxIterations(8)
                .context(SlidingWindowContext.builder().windowSize(2).taskStorage(taskStorage).build())
                .conversationStorer(storer)
                .onToolCall(tc -> System.out.println("[ToolCall] " + tc))
                .onObservation(obs -> System.out.println("[Observation] " + obs))
                .build();

        ChatGeneration result = agent.invoke("请告诉我上海的天气和北京的当前时间。");
        System.out.println("[Final] " + result.getText());
        Assert.assertNotNull(result);
        Assert.assertFalse(result.getText().isBlank());

        List<HistoryInfos> history = conversationStorage.loadAll(appId, userId, sessionId);
        Assert.assertFalse("ConversationStorage should have a final turn", history.isEmpty());
        System.out.println("[ConversationStorage] turns: " + history.size());
    }

    // ── 8. AgentTaskStorage contents after McpAgentExecutor run ──────────────

    @Test
    public void testMcpAgentTaskStorageHasAgentStepEntries() {
        TrackingAgentTaskStorage taskStorage = new TrackingAgentTaskStorage();

        McpAgentExecutor agent = McpAgentExecutor.builder(chainActor)
                .llm(qwenAgent())
                .tools(weatherTool(), timeTool())
                .systemPrompt("你是一名智能助手，使用工具回答问题。")
                .maxIterations(6)
                .context(SlidingWindowContext.builder().windowSize(10).taskStorage(taskStorage).build())
                .onToolCall(tc -> System.out.println("[ToolCall] " + tc))
                .build();

        ChatGeneration result = agent.invoke("上海的天气和当前时间分别是什么？");
        Assert.assertNotNull(result);
        Assert.assertFalse(result.getText().isBlank());

        // The storage must have at least one AGENT_STEP entry
        String taskId = taskStorage.getFirstTaskId();
        Assert.assertNotNull("taskStorage must have recorded a taskId", taskId);

        List<HistoryInfos> steps = taskStorage.loadByTaskId(taskId);
        Assert.assertFalse("at least one AGENT_STEP must be stored", steps.isEmpty());
        Assert.assertTrue("all entries must be AGENT_STEP type",
                steps.stream().allMatch(h -> h.getType() == HistoryInfos.Type.AGENT_STEP));
        Assert.assertTrue("all entries must link to the task via parentId",
                steps.stream().allMatch(h -> taskId.equals(h.getParentId())));

        System.out.println("[AgentTaskStorage] steps recorded: " + steps.size() + ", taskId: " + taskId);
    }

    // ── 9. SlidingWindowContext compresses to TASK_SUMMARY in storage ─────────
    //
    // The LLM may call multiple tools in a single response (parallel tool use),
    // which counts as ONE step. To guarantee compression (requires >= 2 steps),
    // we use a tool chain where the second tool's input depends on the first
    // tool's output — forcing the LLM to call them in two separate iterations.

    /** Step 1: look up weather code for a city. Returns a numeric code. */
    static Tool weatherCodeTool() {
        return Tool.builder()
                .name("get_weather_code")
                .params("city: String")
                .description("Get the weather code for a city. Returns a numeric code. Input is the city name.")
                .func(city -> city + "_code:42")
                .build();
    }

    /** Step 2: decode a weather code returned by get_weather_code. */
    static Tool weatherDecodeTool() {
        return Tool.builder()
                .name("decode_weather_code")
                .params("code: String")
                .description("Decode a weather code (e.g. 'Shanghai_code:42') into a human-readable weather description.")
                .func(code -> code.toString().contains("42") ? "sunny, 25°C" : "cloudy, 18°C")
                .build();
    }

    @Test
    public void testSlidingWindowCompressionCreatesTaskSummaryInStorage() {
        TrackingAgentTaskStorage taskStorage = new TrackingAgentTaskStorage();

        // The two tools form a sequential chain: decode_weather_code requires the
        // output of get_weather_code, so the LLM must call them in two separate
        // tool-call iterations → at least 2 AgentSteps → windowSize=1 triggers compression.
        McpAgentExecutor agent = McpAgentExecutor.builder(chainActor)
                .llm(qwenAgent())
                .tools(weatherCodeTool(), weatherDecodeTool())
                .systemPrompt("你是一名智能助手。请先用 get_weather_code 获取天气代码，再用 decode_weather_code 解码，最后回答用户。")
                .maxIterations(8)
                .context(SlidingWindowContext.builder().windowSize(1).taskStorage(taskStorage).build())
                .onToolCall(tc -> System.out.println("[ToolCall] " + tc))
                .onObservation(obs -> System.out.println("[Observation] " + obs))
                .build();

        ChatGeneration result = agent.invoke("上海今天天气怎么样？请先获取天气代码，再解码后告诉我。");
        System.out.println("[SlidingWindow] Final answer: " + result.getText());
        Assert.assertNotNull(result);
        Assert.assertFalse(result.getText().isBlank());

        String taskId = taskStorage.getFirstTaskId();
        Assert.assertNotNull("taskStorage must have at least one task", taskId);
        List<HistoryInfos> stored = taskStorage.loadByTaskId(taskId);
        System.out.println("[SlidingWindow] storage entries: " + stored.size()
                + " types=" + stored.stream().map(h -> h.getType().name()).toList());

        boolean hasTaskSummary = stored.stream()
                .anyMatch(h -> h.getType() == HistoryInfos.Type.TASK_SUMMARY);
        Assert.assertTrue(
                "TASK_SUMMARY must exist — sequential tool chain guarantees >= 2 steps → compression with windowSize=1",
                hasTaskSummary);

        long summaryCount = stored.stream().filter(h -> h.getType() == HistoryInfos.Type.TASK_SUMMARY).count();
        System.out.println("[SlidingWindow] TASK_SUMMARY count=" + summaryCount
                + ", AGENT_STEP count=" + stored.stream().filter(h -> h.getType() == HistoryInfos.Type.AGENT_STEP).count());
    }

    // ── 10. SlidingWindowContext with LLM summarizer ──────────────────────────

    @Test
    public void testSlidingWindowWithLlmSummarizer() {
        McpAgentExecutor agent = McpAgentExecutor.builder(chainActor)
                .llm(qwenAgent())
                .tools(weatherTool(), timeTool())
                .systemPrompt("你是一名智能助手，使用工具回答问题。")
                .maxIterations(8)
                .context(SlidingWindowContext.builder()
                        .windowSize(1)
                        .summarizer(qwenAgent())  // use LLM to compress old steps
                        .build())
                .onToolCall(tc -> System.out.println("[ToolCall] " + tc))
                .onObservation(obs -> System.out.println("[Observation] " + obs))
                .build();

        ChatGeneration result = agent.invoke("请告诉我上海和北京的天气，以及上海的当前时间。");
        System.out.println("[SlidingWindow+LLM Summarizer] " + result.getText());
        Assert.assertNotNull(result);
        Assert.assertFalse(result.getText().isBlank());
    }

    // ── 11. Concurrent McpAgentExecutor invocations — session isolation ────────

    @Test
    public void testConcurrentMcpAgentInvocationsAreIsolated() throws Exception {
        TrackingAgentTaskStorage taskStorage = new TrackingAgentTaskStorage();

        McpAgentExecutor agent = McpAgentExecutor.builder(chainActor)
                .llm(qwenAgent())
                .tools(weatherTool(), timeTool())
                .systemPrompt("你是一名智能助手。")
                .maxIterations(6)
                .context(SlidingWindowContext.builder().windowSize(5).taskStorage(taskStorage).build())
                .build();

        int concurrency = 3;
        ExecutorService pool = Executors.newFixedThreadPool(concurrency);
        List<Future<String>> futures = new ArrayList<>();

        futures.add(pool.submit(() -> agent.invoke("上海天气怎么样？").getText()));
        futures.add(pool.submit(() -> agent.invoke("北京当前时间是多少？").getText()));
        futures.add(pool.submit(() -> agent.invoke("广州天气怎么样？").getText()));

        pool.shutdown();
        Assert.assertTrue(pool.awaitTermination(60, TimeUnit.SECONDS));

        List<String> results = futures.stream()
                .map(f -> { try { return f.get(); } catch (Exception e) { throw new RuntimeException(e); } })
                .collect(Collectors.toList());

        for (String text : results) {
            Assert.assertNotNull(text);
            Assert.assertFalse("each concurrent invocation must produce a non-blank result", text.isBlank());
        }

        // Each invocation must have produced a distinct task in storage
        List<String> taskIds = taskStorage.getAllTaskIds();
        Assert.assertEquals("each concurrent invocation must have its own taskId",
                concurrency, taskIds.size());

        System.out.println("[Concurrent] taskIds: " + taskIds);
        for (int i = 0; i < results.size(); i++) {
            System.out.println("[Concurrent result " + i + "] " + results.get(i));
        }
    }

    // ── 12. AgentExecutor — multiple calls each produce their own ConversationStorage turn ──

    @Test
    public void testAgentExecutorMultipleCallsEachStoreOneTurn() {
        InMemoryConversationStorage conversationStorage = new InMemoryConversationStorage();
        Long appId = 0L, userId = 5L, sessionId = 500L;

        ConversationBufferMemoryStorer storer = ConversationBufferMemoryStorer.builder()
                .storage(conversationStorage).appId(appId).userId(userId).sessionId(sessionId).build();

        AgentExecutor agent = AgentExecutor.builder(chainActor)
                .llm(qwenAgent())
                .tools(weatherTool(), timeTool())
                .maxIterations(6)
                .conversationStorer(storer)
                .build();

        ChatGeneration r1 = agent.invoke("上海天气怎么样？");
        System.out.println("[MultiCall Turn1] " + r1.getText());
        Assert.assertNotNull(r1);
        Assert.assertFalse(r1.getText().isBlank());

        List<HistoryInfos> after1 = conversationStorage.loadAll(appId, userId, sessionId);
        Assert.assertEquals("after first invocation: 1 turn in storage", 1, after1.size());
        Assert.assertEquals(HistoryInfos.Type.NORMAL, after1.get(0).getType());
        Assert.assertEquals("each turn must have exactly [human, ai] messages",
                2, after1.get(0).getMessages().size());

        ChatGeneration r2 = agent.invoke("北京当前时间是多少？");
        System.out.println("[MultiCall Turn2] " + r2.getText());
        Assert.assertNotNull(r2);
        Assert.assertFalse(r2.getText().isBlank());

        List<HistoryInfos> after2 = conversationStorage.loadAll(appId, userId, sessionId);
        Assert.assertEquals("after second invocation: 2 turns in storage", 2, after2.size());

        for (HistoryInfos turn : after2) {
            Assert.assertEquals(HistoryInfos.Type.NORMAL, turn.getType());
            Assert.assertEquals(2, turn.getMessages().size());
        }
    }

    // ── 13. McpAgentExecutor — verify ConversationStorage has exactly 2 messages per turn ─

    @Test
    public void testMcpAgentConversationStorerWritesTwoMessagesPerTurn() {
        InMemoryConversationStorage conversationStorage = new InMemoryConversationStorage();
        Long appId = 0L, userId = 6L, sessionId = 600L;

        ConversationBufferMemoryStorer storer = ConversationBufferMemoryStorer.builder()
                .storage(conversationStorage).appId(appId).userId(userId).sessionId(sessionId).build();

        McpAgentExecutor agent = McpAgentExecutor.builder(chainActor)
                .llm(qwenAgent())
                .tools(weatherTool())
                .systemPrompt("你是一名智能助手。")
                .maxIterations(6)
                .conversationStorer(storer)
                .build();

        agent.invoke("上海天气怎么样？");
        agent.invoke("北京天气怎么样？");

        List<HistoryInfos> history = conversationStorage.loadAll(appId, userId, sessionId);
        Assert.assertEquals("two agent invocations must produce 2 conversation turns", 2, history.size());

        for (HistoryInfos turn : history) {
            Assert.assertEquals(HistoryInfos.Type.NORMAL, turn.getType());
            Assert.assertEquals("each turn must contain exactly [human, ai] messages", 2, turn.getMessages().size());
        }
    }

    // ── Helper: TrackingAgentTaskStorage ──────────────────────────────────────

    static class TrackingAgentTaskStorage implements AgentTaskStorage {

        private final InMemoryAgentTaskStorage delegate = new InMemoryAgentTaskStorage();
        private final List<String> taskIds = new CopyOnWriteArrayList<>();

        @Override
        public void append(String taskId, HistoryInfos step) {
            if (!taskIds.contains(taskId)) taskIds.add(taskId);
            delegate.append(taskId, step);
        }

        @Override
        public List<HistoryInfos> loadByTaskId(String taskId) {
            return delegate.loadByTaskId(taskId);
        }

        @Override
        public void replace(String taskId, List<HistoryInfos> compacted) {
            delegate.replace(taskId, compacted);
        }

        @Override
        public void clear(String taskId) {
            delegate.clear(taskId);
        }

        String getFirstTaskId() {
            return taskIds.isEmpty() ? null : taskIds.get(0);
        }

        List<String> getAllTaskIds() {
            return new ArrayList<>(taskIds);
        }
    }

    private static AiTokenUsage tokenUsage(ChatGeneration result) {
        if (result.getResponseMetadata() == null) return null;
        Object raw = result.getResponseMetadata().get(AiTokenUsage.METADATA_KEY);
        return raw instanceof AiTokenUsage usage ? usage : null;
    }

    private static AgentExecutionMetrics executionMetrics(ChatGeneration result) {
        if (result.getResponseMetadata() == null) return null;
        Object raw = result.getResponseMetadata().get(AgentExecutionMetrics.METADATA_KEY);
        return raw instanceof AgentExecutionMetrics metrics ? metrics : null;
    }

    private static String formatUsageEvent(AgentTokenUsageEvent event) {
        return "task=" + event.getTaskId()
                + ", deltaPrompt=" + event.getDeltaUsage().getPromptTokens()
                + ", deltaCompletion=" + event.getDeltaUsage().getCompletionTokens()
                + ", deltaTotal=" + event.getDeltaUsage().getTotalTokens()
                + ", totalPrompt=" + event.getTotalUsage().getPromptTokens()
                + ", totalCompletion=" + event.getTotalUsage().getCompletionTokens()
                + ", total=" + event.getTotalUsage().getTotalTokens()
                + ", deltaDurationMs=" + event.getDeltaDurationMs()
                + ", totalDurationMs=" + event.getTotalDurationMs()
                + ", llmDurationMs=" + event.getLlmDurationMs()
                + ", toolDurationMs=" + event.getToolDurationMs()
                + ", llmCalls=" + event.getLlmCalls()
                + ", toolCalls=" + event.getToolCalls();
    }
}
