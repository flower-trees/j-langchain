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
import org.salt.function.flow.context.ContextBus;
import org.salt.jlangchain.TestApplication;
import org.salt.jlangchain.core.ChainActor;
import org.salt.jlangchain.core.agent.AgentPauseException;
import org.salt.jlangchain.core.agent.McpAgentExecutor;
import org.salt.jlangchain.core.agent.memory.AgentTaskContext;
import org.salt.jlangchain.core.common.CallInfo;
import org.salt.jlangchain.core.llm.aliyun.ChatAliyun;
import org.salt.jlangchain.rag.tools.Tool;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.junit4.SpringRunner;

import java.util.Map;

/**
 * 文章 27：Human-in-the-Loop 确认流程
 *
 * <p>演示并验证两套确认场景：
 * <ol>
 *   <li>testUserConfirmYes — 用户批准，LLM 自主路由至 confirm_transfer</li>
 *   <li>testUserConfirmNo  — 用户拒绝，LLM 自主路由至 cancel_transfer</li>
 * </ol>
 *
 * <p>核心模式：工具语义分离 + AgentPauseException + partialContext 跳步恢复
 * <pre>
 *   check_balance       — 收集信息（自动执行）
 *   request_transfer    — 发起请求（抛 AgentPauseException，等待人工确认）
 *   confirm_transfer    — 用户批准后 LLM 自主调用，执行真正转账
 *   cancel_transfer     — 用户拒绝后 LLM 自主调用，取消操作
 * </pre>
 */
@RunWith(SpringRunner.class)
@SpringBootTest(classes = TestApplication.class)
@SpringBootConfiguration
public class Article27HumanInTheLoop {

    @Autowired
    private ChainActor chainActor;

    // ── 场景 1：用户批准，执行转账 ────────────────────────────────────────────

    @Test
    public void testUserConfirmYes() {
        doConfirmFlow("y");
    }

    // ── 场景 2：用户拒绝，取消转账 ────────────────────────────────────────────

    @Test
    public void testUserConfirmNo() {
        doConfirmFlow("n");
    }

    // ── 完整确认流程 ──────────────────────────────────────────────────────────

    /**
     * 完整的 Human-in-the-Loop 确认流程：
     *
     * <pre>
     * 第一次 invoke(question)
     *   → check_balance 成功（step-1 记录到 partialContext）
     *   → request_transfer 抛 AgentPauseException（step-2 记录合成观测）
     * 捕获异常，提示用户确认
     * 第二次 invoke(userDecision, partialContext)
     *   → LLM 看到已完成步骤 + 用户决定
     *   → "y"：调用 confirm_transfer → 转账成功
     *   → "n"：调用 cancel_transfer  → 操作取消
     * </pre>
     */
    private void doConfirmFlow(String simulatedInput) {
        String question = "先查询我的账户余额，确认余额充足后帮我向张三转账 50000 元";

        // ── 工具定义 ──────────────────────────────────────────────────────────

        Tool balanceTool = Tool.builder()
                .name("check_balance")
                .description("查询账户当前余额")
                .params("account: String")
                .func(args -> "账户余额：¥80,000，可用余额充足")
                .build();

        Tool requestTransferTool = Tool.builder()
                .name("request_transfer")
                .description("发起转账请求，系统会暂停等待用户确认后才能真正执行")
                .params("amount: String, to: String")
                .func(args -> {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> map = (Map<String, Object>) args;
                    AgentTaskContext ctx = ContextBus.get().getTransmit(CallInfo.AGENT_TASK_CTX.name());
                    throw new AgentPauseException(
                            "need_confirmation",
                            Map.of("action", "向 " + map.get("to") + " 转账 ¥" + map.get("amount")),
                            ctx);
                })
                .build();

        Tool confirmTransferTool = Tool.builder()
                .name("confirm_transfer")
                .description("用户已批准后调用此工具，执行真正的转账")
                .params("amount: String, to: String")
                .func(args -> {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> map = (Map<String, Object>) args;
                    return "转账成功：已向 " + map.get("to") + " 转账 ¥" + map.get("amount");
                })
                .build();

        Tool cancelTransferTool = Tool.builder()
                .name("cancel_transfer")
                .description("用户已拒绝后调用此工具，取消本次转账")
                .params("reason: String")
                .func(args -> "转账已取消：用户拒绝了本次操作")
                .build();

        McpAgentExecutor agent = McpAgentExecutor.builder(chainActor)
                .llm(ChatAliyun.builder().model("qwen-plus").temperature(0f).build())
                .tools(balanceTool, requestTransferTool, confirmTransferTool, cancelTransferTool)
                .verbose(true)
                .build();

        // ── 第一次 invoke：查余额后，发起转账时暂停 ──────────────────────────

        AgentPauseException paused = null;
        try {
            agent.invoke(question);
            Assert.fail("应当抛出 AgentPauseException");
        } catch (AgentPauseException e) {
            paused = e;
        }

        Assert.assertEquals("need_confirmation", paused.getReason());
        Assert.assertFalse("payload 不应为空", paused.getPayload().isEmpty());
        Assert.assertNotNull("partialContext 不应为 null", paused.getPartialContext());
        Assert.assertTrue("已完成步骤数应 >= 1", paused.getCompletedSteps().size() >= 1);

        System.out.println("\n>>> 需要用户确认");
        System.out.println("    操作: " + paused.getPayload().get("action"));
        System.out.println("    暂停前已完成步骤数: " + paused.getCompletedSteps().size());

        // ── 模拟用户输入，追加为新的 human turn，LLM 自主路由 ────────────────

        String userInput = askUser("确认执行以上操作？(y/n): ", simulatedInput);
        var result = agent.invoke(userInput, paused.getPartialContext());

        System.out.println("\n>>> 执行结果（用户输入 " + simulatedInput + "）");
        System.out.println(result.getText());

        Assert.assertNotNull(result);
        Assert.assertFalse("恢复后结果不应为空", result.getText().isBlank());
    }

    // ── 工具方法 ─────────────────────────────────────────────────────────────

    /** 模拟用户输入（生产环境可替换为 Scanner.nextLine() 或 HTTP 请求）。 */
    private static String askUser(String prompt, String simulatedInput) {
        System.out.print(prompt);
        System.out.println(simulatedInput + "  ← 模拟输入");
        return prompt + simulatedInput;
    }
}
