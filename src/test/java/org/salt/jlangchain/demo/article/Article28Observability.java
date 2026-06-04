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

package org.salt.jlangchain.demo.article;

import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.salt.jlangchain.TestApplication;
import org.salt.jlangchain.ai.common.param.AiTokenUsage;
import org.salt.jlangchain.core.ChainActor;
import org.salt.jlangchain.core.agent.AgentExecutionMetrics;
import org.salt.jlangchain.core.agent.AgentTokenUsageEvent;
import org.salt.jlangchain.core.agent.McpAgentExecutor;
import org.salt.jlangchain.core.llm.aliyun.ChatAliyun;
import org.salt.jlangchain.core.parser.generation.ChatGeneration;
import org.salt.jlangchain.rag.tools.Tool;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.junit4.SpringRunner;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 文章 28：Agent 可观测性——Token 用量统计与执行指标
 *
 * <ol>
 *   <li>testTokenUsageFromMetadata  — 任务完成后从 result 元数据读取累计 Token 用量</li>
 *   <li>testTokenUsageCallback      — 注册 onTokenUsage 回调，每轮 LLM 调用后实时推送增量事件</li>
 *   <li>testExecutionMetrics        — 从 result 元数据读取执行耗时指标（llmDuration / toolDuration）</li>
 *   <li>testCombinedObservability   — 同时启用回调 + 元数据读取，完整展示可观测性接入方式</li>
 * </ol>
 */
@RunWith(SpringRunner.class)
@SpringBootTest(classes = TestApplication.class)
@SpringBootConfiguration
public class Article28Observability {

    @Autowired
    ChainActor chainActor;

    static ChatAliyun qwen() {
        return ChatAliyun.builder()
                .model("qwen-plus")
                .temperature(0f)
                .modelKwargs(Map.of("enable_thinking", false))
                .build();
    }

    static Tool weatherTool() {
        return Tool.builder()
                .name("get_weather")
                .description("查询指定城市的当前天气")
                .params("city: String")
                .func(args -> {
                    @SuppressWarnings("unchecked")
                    String city = ((Map<String, Object>) args).getOrDefault("city", "unknown").toString();
                    return city + "：晴，25°C";
                })
                .build();
    }

    static Tool timeTool() {
        return Tool.builder()
                .name("get_time")
                .description("查询指定城市的当前时间")
                .params("city: String")
                .func(args -> {
                    @SuppressWarnings("unchecked")
                    String city = ((Map<String, Object>) args).getOrDefault("city", "unknown").toString();
                    return city + "：当前时间 14:30";
                })
                .build();
    }

    // ── 测试 1：从 result 元数据读取累计 Token 用量 ──────────────────────────

    /**
     * invoke() 完成后，AiTokenUsage 注入到 result.getResponseMetadata()。
     * 包含整个任务（所有 LLM 轮次合计）的 prompt/completion/total token 数。
     */
    @Test
    public void testTokenUsageFromMetadata() {
        McpAgentExecutor agent = McpAgentExecutor.builder(chainActor)
                .llm(qwen())
                .tools(weatherTool(), timeTool())
                .build();

        ChatGeneration result = agent.invoke("上海现在天气和时间分别是什么？");
        System.out.println("[Final] " + result.getText());

        AiTokenUsage usage = tokenUsage(result);
        Assert.assertNotNull("结果应携带 AiTokenUsage", usage);
        Assert.assertTrue("llmCalls 应 >= 1", usage.getLlmCalls() >= 1);
        Assert.assertTrue("totalTokens 应 > 0", usage.getTotalTokens() > 0);

        System.out.println("[TokenUsage]"
                + " promptTokens=" + usage.getPromptTokens()
                + " completionTokens=" + usage.getCompletionTokens()
                + " totalTokens=" + usage.getTotalTokens()
                + " llmCalls=" + usage.getLlmCalls()
                + " toolCalls=" + usage.getToolCalls());
    }

    // ── 测试 2：onTokenUsage 回调——每轮 LLM 调用后实时推送增量事件 ──────────

    /**
     * onTokenUsage 回调在每轮 LLM 调用（含 tool-call 轮次）完成后触发。
     * AgentTokenUsageEvent 同时携带：
     *   - deltaUsage：本轮增量（promptTokens / completionTokens / totalTokens）
     *   - totalUsage：任务累计用量
     *   - deltaDurationMs：本轮耗时，totalDurationMs：任务累计耗时
     *   - llmDurationMs / toolDurationMs：细分耗时
     */
    @Test
    public void testTokenUsageCallback() {
        AtomicInteger callbackCount = new AtomicInteger(0);

        McpAgentExecutor agent = McpAgentExecutor.builder(chainActor)
                .llm(qwen())
                .tools(weatherTool(), timeTool())
                .onTokenUsage(event -> {
                    int n = callbackCount.incrementAndGet();
                    System.out.println("[onTokenUsage #" + n + "] " + formatEvent(event));
                })
                .build();

        ChatGeneration result = agent.invoke("查一下北京和上海的天气");
        System.out.println("[Final] " + result.getText());

        Assert.assertTrue("onTokenUsage 应至少触发 1 次", callbackCount.get() >= 1);
        System.out.println("[CallbackCount] onTokenUsage 共触发 " + callbackCount.get() + " 次");
    }

    // ── 测试 3：AgentExecutionMetrics 执行耗时指标 ───────────────────────────

    /**
     * invoke() 完成后，AgentExecutionMetrics 注入到 result.getResponseMetadata()。
     * 记录整个任务的时间开销：
     *   durationMs    = 总耗时
     *   llmDurationMs = 所有 LLM 调用耗时之和
     *   toolDurationMs= 所有工具调用耗时之和
     *   llmCalls / toolCalls = 调用次数
     */
    @Test
    public void testExecutionMetrics() {
        McpAgentExecutor agent = McpAgentExecutor.builder(chainActor)
                .llm(qwen())
                .tools(weatherTool())
                .build();

        ChatGeneration result = agent.invoke("北京天气怎么样？");
        System.out.println("[Final] " + result.getText());

        AgentExecutionMetrics metrics = executionMetrics(result);
        Assert.assertNotNull("结果应携带 AgentExecutionMetrics", metrics);
        Assert.assertTrue("durationMs 应 > 0", metrics.getDurationMs() > 0);
        Assert.assertTrue("llmDurationMs 应 > 0", metrics.getLlmDurationMs() > 0);
        Assert.assertTrue("llmCalls 应 >= 1", metrics.getLlmCalls() >= 1);

        System.out.println("[ExecutionMetrics]"
                + " durationMs=" + metrics.getDurationMs()
                + " llmDurationMs=" + metrics.getLlmDurationMs()
                + " toolDurationMs=" + metrics.getToolDurationMs()
                + " llmCalls=" + metrics.getLlmCalls()
                + " toolCalls=" + metrics.getToolCalls());
    }

    // ── 测试 4：回调 + 元数据——完整可观测性接入方式 ──────────────────────────

    /**
     * 同时启用 onTokenUsage 回调（实时流）和从元数据读取（任务结束后汇总），
     * 展示生产环境中两种接入方式的组合使用。
     */
    @Test
    public void testCombinedObservability() {
        AtomicInteger callbackCount = new AtomicInteger(0);

        McpAgentExecutor agent = McpAgentExecutor.builder(chainActor)
                .llm(qwen())
                .tools(weatherTool(), timeTool())
                .onTokenUsage(event -> {
                    callbackCount.incrementAndGet();
                    System.out.println("[实时回调] round=" + callbackCount.get()
                            + " deltaTokens=" + event.getDeltaUsage().getTotalTokens()
                            + " totalTokens=" + event.getTotalUsage().getTotalTokens()
                            + " llmMs=" + event.getLlmDurationMs()
                            + " toolMs=" + event.getToolDurationMs());
                })
                .build();

        ChatGeneration result = agent.invoke("分别查一下上海的天气和当前时间");
        System.out.println("[Final] " + result.getText());

        AiTokenUsage usage = tokenUsage(result);
        AgentExecutionMetrics metrics = executionMetrics(result);

        Assert.assertNotNull(usage);
        Assert.assertNotNull(metrics);
        Assert.assertTrue(callbackCount.get() >= 1);

        System.out.println("\n[任务汇总]");
        System.out.println("  Token 用量: prompt=" + usage.getPromptTokens()
                + " completion=" + usage.getCompletionTokens()
                + " total=" + usage.getTotalTokens());
        System.out.println("  执行耗时:   total=" + metrics.getDurationMs() + "ms"
                + " llm=" + metrics.getLlmDurationMs() + "ms"
                + " tool=" + metrics.getToolDurationMs() + "ms");
        System.out.println("  调用次数:   llm=" + metrics.getLlmCalls()
                + " tool=" + metrics.getToolCalls());
    }

    // ── 工具方法 ─────────────────────────────────────────────────────────────

    private static AiTokenUsage tokenUsage(ChatGeneration result) {
        if (result.getResponseMetadata() == null) return null;
        Object raw = result.getResponseMetadata().get(AiTokenUsage.METADATA_KEY);
        return raw instanceof AiTokenUsage u ? u : null;
    }

    private static AgentExecutionMetrics executionMetrics(ChatGeneration result) {
        if (result.getResponseMetadata() == null) return null;
        Object raw = result.getResponseMetadata().get(AgentExecutionMetrics.METADATA_KEY);
        return raw instanceof AgentExecutionMetrics m ? m : null;
    }

    private static String formatEvent(AgentTokenUsageEvent event) {
        return "deltaTokens=" + event.getDeltaUsage().getTotalTokens()
                + " totalTokens=" + event.getTotalUsage().getTotalTokens()
                + " llmMs=" + event.getLlmDurationMs()
                + " toolMs=" + event.getToolDurationMs()
                + " llmCalls=" + event.getLlmCalls()
                + " toolCalls=" + event.getToolCalls();
    }
}
