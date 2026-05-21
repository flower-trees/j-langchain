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
import org.salt.jlangchain.core.ChainActor;
import org.salt.jlangchain.core.agent.*;
import org.salt.jlangchain.core.agent.memory.AgentTaskContext;
import org.salt.jlangchain.core.common.CallInfo;
import org.salt.jlangchain.core.llm.aliyun.ChatAliyun;
import org.salt.jlangchain.rag.tools.Tool;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.junit4.SpringRunner;
import org.salt.function.flow.context.ContextBus;

import java.util.Map;

/**
 * 文章 26：Agent 停止类型验证
 *
 * <p>演示并验证三类新增运行时停止：
 * <ol>
 *   <li>testMaxSteps                  — maxIterations 超限 → AgentAbortException(MAX_STEPS)</li>
 *   <li>testTimeout                   — 工具执行超时     → AgentAbortException(TIMEOUT)</li>
 *   <li>testConsecutiveToolFailures   — LLM 连续调用失败工具 → AgentAbortException(CONSECUTIVE_TOOL_FAILURES)</li>
 *   <li>testToolRetry                 — 框架自动重试，瞬时失败对 LLM 透明</li>
 *   <li>testPauseAndResume            — 上层 tool 主动暂停 → AgentPauseException，然后恢复执行</li>
 * </ol>
 */
@RunWith(SpringRunner.class)
@SpringBootTest(classes = TestApplication.class)
@SpringBootConfiguration
public class Article26AgentStopTypes {

    @Autowired
    private ChainActor chainActor;

    // ── 测试 1：MAX_STEPS ────────────────────────────────────────────────────

    /**
     * maxIterations(1)：第一轮 LLM 调用工具后，下一轮 shouldContinue 命中 i >= maxIter，
     * 循环退出时输出仍是 ChatPromptValue → 抛 AgentAbortException(MAX_STEPS)。
     */
    @Test
    public void testMaxSteps() {
        Tool weatherTool = Tool.builder()
                .name("get_weather")
                .description("查询指定城市的天气")
                .params("city: String")
                .func(args -> {
                    @SuppressWarnings("unchecked")
                    String city = ((Map<String, Object>) args).getOrDefault("city", "unknown").toString();
                    return city + ": 晴，26°C";
                })
                .build();

        McpAgentExecutor agent = McpAgentExecutor.builder(chainActor)
                .llm(ChatAliyun.builder().model("qwen-plus").temperature(0f).build())
                .tools(weatherTool)
                .maxIterations(1)
                .verbose(true)
                .build();

        try {
            // 需要查询多个城市，LLM 第一轮只能查一个，第二轮被 maxIterations 截断
            agent.invoke("依次查询北京、上海、广州、深圳四个城市的天气，然后汇总");
            Assert.fail("应当抛出 AgentAbortException");
        } catch (AgentAbortException e) {
            Assert.assertEquals(AgentAbortReason.MAX_STEPS, e.getReason());
            System.out.println("[testMaxSteps] reason=" + e.getReason()
                    + "  steps=" + e.getCompletedSteps().size()
                    + "  msg=" + e.getMessage());
        }
    }

    // ── 测试 2：TIMEOUT ──────────────────────────────────────────────────────

    /**
     * 工具执行耗时 4 秒，maxDurationSeconds(1)。
     * 工具返回后 shouldContinue 检查已超时 → AgentAbortException(TIMEOUT)。
     */
    @Test
    public void testTimeout() {
        Tool slowTool = Tool.builder()
                .name("slow_search")
                .description("执行一次耗时较长的搜索")
                .params("query: String")
                .func(args -> {
                    try { Thread.sleep(4000); } catch (InterruptedException ignored) {}
                    return "搜索完成";
                })
                .build();

        McpAgentExecutor agent = McpAgentExecutor.builder(chainActor)
                .llm(ChatAliyun.builder().model("qwen-plus").temperature(0f).build())
                .tools(slowTool)
                .maxDurationSeconds(1)
                .verbose(true)
                .build();

        try {
            agent.invoke("帮我搜索一些资料");
            Assert.fail("应当抛出 AgentAbortException");
        } catch (AgentAbortException e) {
            Assert.assertEquals(AgentAbortReason.TIMEOUT, e.getReason());
            System.out.println("[testTimeout] reason=" + e.getReason()
                    + "  msg=" + e.getMessage());
        }
    }

    // ── 测试 3：CONSECUTIVE_TOOL_FAILURES ───────────────────────────────────

    /**
     * 工具每次调用都抛 RuntimeException，maxConsecutiveToolFailures(1)。
     * LLM 第一次调用失败即触发 → AgentAbortException(CONSECUTIVE_TOOL_FAILURES)。
     */
    @Test
    public void testConsecutiveToolFailures() {
        Tool failingTool = Tool.builder()
                .name("unstable_api")
                .description("调用一个不稳定的外部 API")
                .params("query: String")
                .func(args -> {
                    throw new RuntimeException("连接超时，无法访问 API");
                })
                .build();

        McpAgentExecutor agent = McpAgentExecutor.builder(chainActor)
                .llm(ChatAliyun.builder().model("qwen-plus").temperature(0f).build())
                .tools(failingTool)
                .maxConsecutiveToolFailures(1)
                .verbose(true)
                .build();

        try {
            agent.invoke("调用 API 查询最新数据");
            Assert.fail("应当抛出 AgentAbortException");
        } catch (AgentAbortException e) {
            Assert.assertEquals(AgentAbortReason.CONSECUTIVE_TOOL_FAILURES, e.getReason());
            System.out.println("[testConsecutiveToolFailures] reason=" + e.getReason()
                    + "  steps=" + e.getCompletedSteps().size()
                    + "  msg=" + e.getMessage());
        }
    }

    // ── 测试 4：toolRetry（框架自动重试，对 LLM 透明）────────────────────────

    /**
     * 工具前两次调用抛异常，第三次成功。toolRetry(2) 使框架静默重试，
     * LLM 最终收到成功的 observation，不感知中间失败。
     */
    @Test
    public void testToolRetry() {
        java.util.concurrent.atomic.AtomicInteger callCount = new java.util.concurrent.atomic.AtomicInteger(0);

        Tool flakyTool = Tool.builder()
                .name("flaky_api")
                .description("查询数据（偶发网络抖动）")
                .params("query: String")
                .func(args -> {
                    int n = callCount.incrementAndGet();
                    if (n <= 2) throw new RuntimeException("网络抖动 attempt=" + n);
                    return "查询成功，数据返回正常";
                })
                .build();

        McpAgentExecutor agent = McpAgentExecutor.builder(chainActor)
                .llm(ChatAliyun.builder().model("qwen-plus").temperature(0f).build())
                .tools(flakyTool)
                .toolRetry(2)
                .verbose(true)
                .build();

        var result = agent.invoke("通过 flaky_api 查询最新数据");

        Assert.assertNotNull("框架重试后应正常返回结果", result);
        Assert.assertFalse("结果文本不应为空", result.getText().isBlank());
        Assert.assertEquals("工具应被调用 3 次（2 次失败 + 1 次成功）", 3, callCount.get());
        System.out.println("[testToolRetry] callCount=" + callCount.get()
                + "  result=" + result.getText());
    }

    // ── 测试 4：AgentPauseException（暂停 + 恢复） ───────────────────────────

    /**
     * 上层工具主动抛 AgentPauseException("need_approval", ...)。
     * 框架捕获后保存 partialContext，调用方可以用它恢复执行。
     *
     * <p>流程：
     * <ol>
     *   <li>第一次 invoke：LLM 调用 transfer_money → 工具抛 AgentPauseException</li>
     *   <li>捕获异常，验证 reason / payload</li>
     *   <li>（模拟用户批准后）第二次 invoke 带 partialContext 恢复，工具改为正常返回</li>
     * </ol>
     */
    @Test
    public void testPauseAndResume() {
        // ── 第一次：工具需要审批，抛 AgentPauseException ──────────────────
        Tool transferTool = Tool.builder()
                .name("transfer_money")
                .description("执行转账操作")
                .params("amount: String, to: String")
                .func(args -> {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> map = (Map<String, Object>) args;
                    AgentTaskContext ctx = ContextBus.get().getTransmit(CallInfo.AGENT_TASK_CTX.name());
                    // 模拟金额超限，需要人工审批
                    throw new AgentPauseException(
                            "need_approval",
                            Map.of("amount", map.getOrDefault("amount", ""),
                                   "to",     map.getOrDefault("to", ""),
                                   "reason", "单笔金额超过自动审批限额"),
                            ctx);
                })
                .build();

        McpAgentExecutor agent = McpAgentExecutor.builder(chainActor)
                .llm(ChatAliyun.builder().model("qwen-plus").temperature(0f).build())
                .tools(transferTool)
                .verbose(true)
                .build();

        AgentPauseException paused = null;
        try {
            agent.invoke("帮我向张三转账 50000 元");
            Assert.fail("应当抛出 AgentPauseException");
        } catch (AgentPauseException e) {
            paused = e;
            Assert.assertEquals("need_approval", e.getReason());
            Assert.assertFalse("payload 不应为空", e.getPayload().isEmpty());
            Assert.assertNotNull("partialContext 不应为 null", e.getPartialContext());
            System.out.println("[testPauseAndResume] 暂停 reason=" + e.getReason()
                    + "  payload=" + e.getPayload());
        }
        Assert.assertNotNull("应当捕获到 AgentPauseException", paused);

        // ── 第二次：模拟审批通过，换一个正常工具恢复执行 ─────────────────
        Tool approvedTransferTool = Tool.builder()
                .name("transfer_money")
                .description("执行转账操作（已审批）")
                .params("amount: String, to: String")
                .func(args -> {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> map = (Map<String, Object>) args;
                    return "转账成功：向 " + map.getOrDefault("to", "")
                            + " 转账 " + map.getOrDefault("amount", "") + " 元";
                })
                .build();

        McpAgentExecutor approvedAgent = McpAgentExecutor.builder(chainActor)
                .llm(ChatAliyun.builder().model("qwen-plus").temperature(0f).build())
                .tools(approvedTransferTool)
                .verbose(true)
                .build();

        AgentTaskContext savedCtx = paused.getPartialContext();
        var result = approvedAgent.invoke("帮我向张三转账 50000 元", savedCtx);

        Assert.assertNotNull("恢复后应有结果", result);
        Assert.assertFalse("恢复后结果不应为空", result.getText().isBlank());
        System.out.println("[testPauseAndResume] 恢复结果:\n" + result.getText());
    }
}
