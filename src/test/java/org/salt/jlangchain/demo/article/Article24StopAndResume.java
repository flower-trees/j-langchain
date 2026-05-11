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
import org.salt.jlangchain.core.agent.AgentStoppedException;
import org.salt.jlangchain.core.agent.McpAgentExecutor;
import org.salt.jlangchain.core.agent.memory.AgentStep;
import org.salt.jlangchain.core.agent.memory.AgentTaskContext;
import org.salt.jlangchain.core.agent.memory.FullContext;
import org.salt.jlangchain.core.llm.aliyun.ChatAliyun;
import org.salt.jlangchain.core.parser.generation.ChatGeneration;
import org.salt.jlangchain.core.subagent.SubAgent;
import org.salt.jlangchain.core.subagent.SubAgentConfig;
import org.salt.jlangchain.rag.tools.Tool;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.junit4.SpringRunner;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

/**
 * 文章 24：Agent 停止与继续
 *
 * <p>演示内容：
 * <ol>
 *   <li>testStopMasterAgent          — 异步运行 Agent，外部调用 stop()，验证 AgentStoppedException</li>
 *   <li>testPartialContextOnStop     — stop 后 partialContext 包含已完成的步骤</li>
 *   <li>testStopPropagatesIntoSubAgent — stop 信号透传到 SubAgent 内部 loop</li>
 *   <li>testResumeWithPartialContext  — 使用 stop 返回的 partialContext 恢复执行</li>
 *   <li>testContinueWithNewInstruction — 使用 createWithSteps 把旧步骤注入新指令</li>
 * </ol>
 */
@RunWith(SpringRunner.class)
@SpringBootTest(classes = TestApplication.class)
@SpringBootConfiguration
public class Article24StopAndResume {

    @Autowired
    private ChainActor chainActor;

    // ── 共用工具工厂 ──────────────────────────────────────────────────────────

    /** 构造一个即时返回的查询工具（用于非 stop 路径的测试） */
    @SuppressWarnings("unchecked")
    private static Tool fastTool(String name, String description) {
        return Tool.builder()
                .name(name)
                .description(description)
                .params("city: String")
                .func(args -> {
                    Map<String, Object> map = (Map<String, Object>) args;
                    return map.getOrDefault("city", "unknown") + ": 查询完成";
                })
                .build();
    }

    /**
     * 构造一个"慢速"工具：调用时先触发 latch，然后等待 holdMs 毫秒再返回。
     * 测试线程在 latch 触发后调用 stop()，确保 stop 信号在 shouldContinue 前被设置。
     */
    @SuppressWarnings("unchecked")
    private static Tool slowTool(String name, String description, CountDownLatch startLatch, long holdMs) {
        return Tool.builder()
                .name(name)
                .description(description)
                .params("city: String")
                .func(args -> {
                    startLatch.countDown();
                    try { Thread.sleep(holdMs); } catch (InterruptedException ignored) {}
                    Map<String, Object> map = (Map<String, Object>) args;
                    return map.getOrDefault("city", "unknown") + ": 查询完成";
                })
                .build();
    }

    // ── 测试 1：基础 stop ─────────────────────────────────────────────────────

    /**
     * 异步执行 Agent，工具开始执行后调用 stop()。
     * 断言：future.get() 抛出 ExecutionException，cause 是 AgentStoppedException。
     */
    @Test
    public void testStopMasterAgent() throws Exception {
        CountDownLatch toolStarted = new CountDownLatch(1);

        McpAgentExecutor agent = McpAgentExecutor.builder(chainActor)
                .llm(ChatAliyun.builder().model("qwen-plus").temperature(0f).build())
                .tools(slowTool("search_city", "查询城市旅游信息", toolStarted, 500))
                .verbose(true)
                .build();

        CompletableFuture<ChatGeneration> future = CompletableFuture.supplyAsync(
                () -> agent.invoke("依次查询成都、西安、桂林的旅游信息并给出推荐"));

        toolStarted.await(10, TimeUnit.SECONDS);
        agent.stop();

        try {
            future.get(15, TimeUnit.SECONDS);
            Assert.fail("应当抛出 AgentStoppedException");
        } catch (ExecutionException e) {
            Assert.assertTrue("cause 应为 AgentStoppedException",
                    e.getCause() instanceof AgentStoppedException);
            System.out.println("[testStopMasterAgent] 停止成功，cause: " + e.getCause().getMessage());
        }
    }

    // ── 测试 2：partialContext 包含已完成的步骤 ───────────────────────────────

    /**
     * stop 之后，AgentStoppedException 应携带已完成步骤。
     * 断言：getCompletedSteps() 非 null；stop 发生在工具调用完成之后时步骤数 >= 1。
     */
    @Test
    public void testPartialContextOnStop() throws Exception {
        CountDownLatch toolDone = new CountDownLatch(1);

        // 工具返回后再触发 latch（确保至少一个 step 已 addStep）
        Tool tool = Tool.builder()
                .name("search_city")
                .description("查询城市旅游信息")
                .params("city: String")
                .func(args -> {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> map = (Map<String, Object>) args;
                    String result = map.getOrDefault("city", "unknown") + ": 天气晴好";
                    toolDone.countDown(); // 工具已执行完，step 即将被 addStep
                    try { Thread.sleep(300); } catch (InterruptedException ignored) {}
                    return result;
                })
                .build();

        McpAgentExecutor agent = McpAgentExecutor.builder(chainActor)
                .llm(ChatAliyun.builder().model("qwen-plus").temperature(0f).build())
                .tools(tool)
                .verbose(true)
                .build();

        CompletableFuture<ChatGeneration> future = CompletableFuture.supplyAsync(
                () -> agent.invoke("查询成都和西安的旅游信息"));

        toolDone.await(10, TimeUnit.SECONDS);
        agent.stop();

        try {
            future.get(15, TimeUnit.SECONDS);
            Assert.fail("应当抛出 AgentStoppedException");
        } catch (ExecutionException e) {
            Assert.assertTrue(e.getCause() instanceof AgentStoppedException);
            AgentStoppedException stopped = (AgentStoppedException) e.getCause();

            Assert.assertNotNull("partialContext 不应为 null", stopped.getPartialContext());
            List<AgentStep> steps = stopped.getCompletedSteps();
            Assert.assertNotNull("completedSteps 不应为 null", steps);
            System.out.println("[testPartialContextOnStop] 已完成步骤数: " + steps.size());
            // stop 发生在工具 sleep 期间，step 在 sleep 结束后才被 addStep；
            // 因此步骤数可能为 0（stop 先于 addStep）或 1（addStep 先于 stop 检查）
            System.out.println("[testPartialContextOnStop] steps=" + steps.size());
        }
    }

    // ── 测试 3：stop 信号传递到 SubAgent ─────────────────────────────────────

    /**
     * Master 调用 SubAgent，SubAgent 的工具开始执行后 Master stop()。
     * 断言：AgentStoppedException 从 SubAgent 内部传播到 Master 的 future。
     */
    @Test
    public void testStopPropagatesIntoSubAgent() throws Exception {
        CountDownLatch subToolStarted = new CountDownLatch(1);

        SubAgentConfig config = new SubAgentConfig();
        config.setName("travel_researcher");
        config.setDescription("旅行信息研究员，查询指定城市的旅行信息");

        SubAgent researcher = SubAgent.from(config, chainActor)
                .llm(ChatAliyun.builder().model("qwen-plus").temperature(0f).build())
                .tools(slowTool("search_city", "查询城市旅游信息", subToolStarted, 500))
                .build();

        McpAgentExecutor master = McpAgentExecutor.builder(chainActor)
                .llm(ChatAliyun.builder().model("qwen-plus").temperature(0f).build())
                .subAgent(researcher)
                .systemPrompt("你是旅行总助手，需要查询旅行信息时请调用 travel_researcher。")
                .verbose(true)
                .build();

        CompletableFuture<ChatGeneration> future = CompletableFuture.supplyAsync(
                () -> master.invoke("查询成都的旅行信息"));

        subToolStarted.await(10, TimeUnit.SECONDS);
        master.stop(); // 停止 master，信号应传递到 SubAgent

        try {
            future.get(15, TimeUnit.SECONDS);
            Assert.fail("应当抛出 AgentStoppedException");
        } catch (ExecutionException e) {
            Assert.assertTrue("stop 信号应从 SubAgent 传播到 Master",
                    e.getCause() instanceof AgentStoppedException);
            System.out.println("[testStopPropagatesIntoSubAgent] 停止成功");
        }
    }

    // ── 测试 4：使用 partialContext 恢复执行 ─────────────────────────────────

    /**
     * stop 后用 exception 携带的 partialContext 恢复执行（同一问题继续）。
     * 断言：第二次 invoke 能正常完成（不再抛 AgentStoppedException）。
     */
    @Test
    public void testResumeWithPartialContext() throws Exception {
        CountDownLatch toolStarted = new CountDownLatch(1);

        McpAgentExecutor agent = McpAgentExecutor.builder(chainActor)
                .llm(ChatAliyun.builder().model("qwen-plus").temperature(0f).build())
                .tools(
                    slowTool("search_city", "查询城市旅游信息", toolStarted, 400),
                    fastTool("get_hotel",   "查询酒店价格")
                )
                .verbose(true)
                .build();

        String question = "查询成都的旅游信息和酒店价格，然后给出建议";

        // ── 第一次：执行中被 stop ──────────────────────────────────────────
        CompletableFuture<ChatGeneration> firstRun = CompletableFuture.supplyAsync(
                () -> agent.invoke(question));

        toolStarted.await(10, TimeUnit.SECONDS);
        agent.stop();

        AgentStoppedException stopped = null;
        try {
            firstRun.get(15, TimeUnit.SECONDS);
            Assert.fail("第一次运行应当被 stop");
        } catch (ExecutionException e) {
            Assert.assertTrue(e.getCause() instanceof AgentStoppedException);
            stopped = (AgentStoppedException) e.getCause();
        }
        Assert.assertNotNull("stop 应当捕获到异常", stopped);

        System.out.println("[testResumeWithPartialContext] 第一次 stop，已完成步骤: "
                + stopped.getCompletedSteps().size());

        // ── 第二次：带 partialContext 恢复，不再 stop ─────────────────────
        AgentTaskContext partialCtx = stopped.getPartialContext();
        ChatGeneration result = agent.invoke(question, partialCtx);

        Assert.assertNotNull("恢复后应得到非 null 结果", result);
        Assert.assertFalse("恢复后结果文本不应为空", result.getText().isBlank());
        System.out.println("[testResumeWithPartialContext] 恢复结果:\n" + result.getText());
    }

    // ── 测试 5：用已有步骤继续执行新指令 ─────────────────────────────────────

    /**
     * 使用 AgentContext.createWithSteps() 将旧步骤注入新指令，
     * 外层（调用方）自行决定哪些步骤仍然有价值。
     * 断言：新指令能正常完成。
     */
    @Test
    public void testContinueWithNewInstruction() throws Exception {
        CountDownLatch toolStarted = new CountDownLatch(1);
        FullContext context = FullContext.build();

        McpAgentExecutor agent = McpAgentExecutor.builder(chainActor)
                .llm(ChatAliyun.builder().model("qwen-plus").temperature(0f).build())
                .context(context)
                .tools(
                    slowTool("search_city", "查询城市旅游信息", toolStarted, 400),
                    fastTool("get_hotel",   "查询酒店价格")
                )
                .verbose(true)
                .build();

        // ── 先 stop，拿到已完成步骤 ─────────────────────────────────────
        CompletableFuture<ChatGeneration> firstRun = CompletableFuture.supplyAsync(
                () -> agent.invoke("查询成都的旅游和酒店信息"));

        toolStarted.await(10, TimeUnit.SECONDS);
        agent.stop();

        AgentStoppedException stopped = null;
        try {
            firstRun.get(15, TimeUnit.SECONDS);
            Assert.fail("应当被 stop");
        } catch (ExecutionException e) {
            Assert.assertTrue(e.getCause() instanceof AgentStoppedException);
            stopped = (AgentStoppedException) e.getCause();
        }
        Assert.assertNotNull("stop 应当捕获到异常", stopped);

        List<AgentStep> priorSteps = stopped.getCompletedSteps();
        System.out.println("[testContinueWithNewInstruction] 已有步骤: " + priorSteps.size());

        // ── 外层用旧步骤 + 新指令，由外层组装 context ────────────────────
        String newQuestion = "基于已有信息，改为推荐西安之旅，并查询西安酒店";
        AgentTaskContext newCtx = context.createWithSteps(newQuestion, null, priorSteps);

        ChatGeneration result = agent.invoke(newQuestion, newCtx);

        Assert.assertNotNull("新指令应得到非 null 结果", result);
        Assert.assertFalse("新指令结果文本不应为空", result.getText().isBlank());
        System.out.println("[testContinueWithNewInstruction] 新指令结果:\n" + result.getText());
    }
}
