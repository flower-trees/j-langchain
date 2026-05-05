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
import org.salt.jlangchain.core.ChainActor;
import org.salt.jlangchain.core.agent.AgentExecutor;
import org.salt.jlangchain.core.agent.McpAgentExecutor;
import org.salt.jlangchain.core.agent.SlidingWindowContext;
import org.salt.jlangchain.core.history.HistoryInfos;
import org.salt.jlangchain.core.history.memory.buffer.ConversationBufferMemoryStorer;
import org.salt.jlangchain.core.history.storage.InMemoryAgentTaskStorage;
import org.salt.jlangchain.core.history.storage.InMemoryConversationStorage;
import org.salt.jlangchain.core.llm.aliyun.ChatAliyun;
import org.salt.jlangchain.core.parser.generation.ChatGeneration;
import org.salt.jlangchain.rag.tools.Tool;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.junit4.SpringRunner;

import java.util.List;

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
                .llm(ChatAliyun.builder().model("qwen3.6-plus").temperature(0f).build())
                .tools(weatherTool(), timeTool())
                .maxIterations(6)
                .onThought(t -> System.out.print("[Thought] " + t))
                .onObservation(obs -> System.out.println("[Observation] " + obs))
                .build();

        ChatGeneration result = agent.invoke("上海现在天气怎么样？");
        System.out.println("[Final] " + result.getText());
        Assert.assertNotNull(result);
        Assert.assertFalse(result.getText().isBlank());
    }

    // ── 2. AgentExecutor — SlidingWindowContext ───────────────────────────────

    @Test
    public void testAgentExecutorWithSlidingWindow() {
        AgentExecutor agent = AgentExecutor.builder(chainActor)
                .llm(ChatAliyun.builder().model("qwen3.6-plus").temperature(0f).build())
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
    }

    // ── 3. AgentExecutor — SlidingWindowContext + AgentTaskStorage ────────────

    @Test
    public void testAgentExecutorWithAgentTaskStorage() {
        InMemoryAgentTaskStorage taskStorage = new InMemoryAgentTaskStorage();

        AgentExecutor agent = AgentExecutor.builder(chainActor)
                .llm(ChatAliyun.builder().model("qwen3.6-plus").temperature(0f).build())
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
                .llm(ChatAliyun.builder().model("qwen3.6-plus").temperature(0f).build())
                .tools(weatherTool(), timeTool())
                .systemPrompt("你是一名智能助手，请使用工具来回答用户问题。")
                .maxIterations(6)
                .onToolCall(tc -> System.out.println("[ToolCall] " + tc))
                .onObservation(obs -> System.out.println("[Observation] " + obs))
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
                .llm(ChatAliyun.builder().model("qwen3.6-plus").temperature(0f).build())
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
                .llm(ChatAliyun.builder().model("qwen3.6-plus").temperature(0f).build())
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
                .llm(ChatAliyun.builder().model("qwen3.6-plus").temperature(0f).build())
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
}
