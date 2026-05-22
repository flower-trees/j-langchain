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

import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.salt.function.flow.context.ContextBus;
import org.salt.jlangchain.TestApplication;
import org.salt.jlangchain.ai.common.param.AiTokenUsage;
import org.salt.jlangchain.core.ChainActor;
import org.salt.jlangchain.core.agent.memory.AgentTaskContext;
import org.salt.jlangchain.core.common.CallInfo;
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
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Control-flow tests for the ReAct {@link AgentExecutor}.
 */
@RunWith(SpringRunner.class)
@SpringBootTest(classes = TestApplication.class)
@SpringBootConfiguration
public class AgentExecutorControlTest {

    @Autowired
    ChainActor chainActor;

    static ChatAliyun qwenFlash() {
        return ChatAliyun.builder()
                .model("qwen3.5-flash")
                .temperature(0f)
                .modelKwargs(Map.of("enable_thinking", false))
                .build();
    }

    @Test
    public void testToolRetryRetriesBeforeReturningObservation() {
        AtomicInteger calls = new AtomicInteger();
        List<AgentTokenUsageEvent> usageEvents = new ArrayList<>();
        Tool unstableTool = Tool.builder()
                .name("unstable_tool")
                .description("A required test tool. Input should be the literal string city. The tool fails once then succeeds.")
                .func(input -> {
                    if (calls.incrementAndGet() == 1) {
                        throw new RuntimeException("temporary failure");
                    }
                    return "tool ok";
                })
                .build();

        ChatGeneration result = AgentExecutor.builder(chainActor)
                .llm(qwenFlash())
                .tools(unstableTool)
                .toolRetry(1)
                .maxIterations(4)
                .onTokenUsage(event -> {
                    usageEvents.add(event);
                    System.out.println("[AgentExecutor tokenUsage event] " + formatUsageEvent(event));
                })
                .build()
                .invoke("""
                        This is a tool-routing test. Your first response must be exactly in this ReAct form:
                        Thought: I need to call the required test tool.
                        Action: unstable_tool
                        Action Input: city

                        Do not provide a Final Answer until after you receive an Observation.
                        """);

        Assert.assertNotNull(result);
        Assert.assertFalse(result.getText().isBlank());
        Assert.assertEquals("tool should be called once plus one retry", 2, calls.get());
        AiTokenUsage usage = tokenUsage(result);
        Assert.assertNotNull("agent result should expose aggregate token usage", usage);
        Assert.assertTrue("agent should record LLM calls", usage.getLlmCalls() >= 1);
        Assert.assertTrue("agent should record requested tool calls", usage.getToolCalls() >= 1);
        Assert.assertTrue("provider should return total token usage", usage.getTotalTokens() > 0);
        Assert.assertFalse("token usage events should be emitted during execution", usageEvents.isEmpty());
        AgentExecutionMetrics metrics = executionMetrics(result);
        Assert.assertNotNull("agent result should expose execution metrics", metrics);
        Assert.assertTrue("agent duration should be recorded", metrics.getDurationMs() >= metrics.getLlmDurationMs());
        System.out.println("[AgentExecutor tokenUsage] " + usage);
        System.out.println("[AgentExecutor executionMetrics] " + metrics);
    }

    @Test
    public void testConsecutiveToolFailuresAbortWithPartialContext() {
        Tool failingTool = Tool.builder()
                .name("failing_tool")
                .description("A required test tool. Input should be the literal string city. This tool always fails.")
                .func(input -> { throw new RuntimeException("boom"); })
                .build();

        try {
            AgentExecutor.builder(chainActor)
                    .llm(qwenFlash())
                    .tools(failingTool)
                    .maxConsecutiveToolFailures(1)
                    .maxIterations(4)
                    .build()
                    .invoke("You must call failing_tool exactly once with Action Input city. Do not answer directly.");
            Assert.fail("expected AgentAbortException");
        } catch (AgentAbortException e) {
            Assert.assertEquals(AgentAbortReason.CONSECUTIVE_TOOL_FAILURES, e.getReason());
            Assert.assertNotNull(e.getPartialContext());
            Assert.assertEquals(1, e.getCompletedSteps().size());
            Assert.assertTrue(e.getCompletedSteps().get(0).getScratchpadText()
                    .contains("Tool execution error: boom"));
            Assert.assertTrue("partial context should keep token usage",
                    e.getPartialContext().getTokenUsage().getLlmCalls() >= 1);
            System.out.println("[AgentExecutor partial tokenUsage] " + e.getPartialContext().getTokenUsage());
            System.out.println("[AgentExecutor partial executionMetrics] " + e.getPartialContext().getExecutionMetrics());
        }
    }

    @Test
    public void testMaxIterationsAbortWhenModelStillRequestsAction() {
        Tool echoTool = Tool.builder()
                .name("echo")
                .description("A required test tool. Input should be the literal string first. It echoes input.")
                .func(input -> input)
                .build();

        try {
            AgentExecutor.builder(chainActor)
                    .llm(qwenFlash())
                    .tools(echoTool)
                    .maxIterations(1)
                    .build()
                    .invoke("You must call echo exactly once with Action Input first. Do not answer directly before using the tool.");
            Assert.fail("expected AgentAbortException");
        } catch (AgentAbortException e) {
            Assert.assertEquals(AgentAbortReason.MAX_STEPS, e.getReason());
            Assert.assertNotNull(e.getPartialContext());
            Assert.assertEquals(1, e.getCompletedSteps().size());
        }
    }

    @Test
    public void testResumeAddsFollowUpToReactPrompt() {
        AtomicInteger pauseCalls = new AtomicInteger();
        Tool pauseTool = Tool.builder()
                .name("pause_tool")
                .description("A required approval tool. Input should be approval. It pauses the first time and returns approved later.")
                .func(input -> {
                    if (pauseCalls.incrementAndGet() > 1) {
                        return "approved";
                    }
                    AgentTaskContext ctx = ContextBus.get().getTransmit(CallInfo.AGENT_TASK_CTX.name());
                    throw new AgentPauseException("wait_user", ctx);
                })
                .build();
        AgentExecutor pauseAgent = AgentExecutor.builder(chainActor)
                .llm(qwenFlash())
                .tools(pauseTool)
                .maxIterations(4)
                .build();

        AgentTaskContext partialContext;
        try {
            pauseAgent.invoke("You must call pause_tool exactly once with Action Input approval. Do not answer directly.");
            Assert.fail("expected AgentPauseException");
            return;
        } catch (AgentPauseException e) {
            partialContext = e.getPartialContext();
            Assert.assertNotNull(partialContext);
            Assert.assertEquals(1, partialContext.getCompletedSteps().size());
            Assert.assertTrue(partialContext.getCompletedSteps().get(0).getScratchpadText()
                    .contains("[paused: wait_user]"));
        }

        List<String> prompts = new ArrayList<>();
        List<AgentTokenUsageEvent> usageEvents = new ArrayList<>();
        ChatGeneration result = AgentExecutor.builder(chainActor)
                .llm(qwenFlash())
                .tools(pauseTool)
                .maxIterations(4)
                .onLlm(prompts::add)
                .onTokenUsage(event -> {
                    usageEvents.add(event);
                    System.out.println("[AgentExecutor resume tokenUsage event] " + formatUsageEvent(event));
                })
                .build()
                .invoke("同意，继续。请不要再调用工具，直接给出 Final Answer。", partialContext);

        Assert.assertNotNull(result);
        Assert.assertFalse(result.getText().isBlank());
        Assert.assertFalse(prompts.isEmpty());
        String resumePrompt = prompts.get(0);
        Assert.assertTrue(resumePrompt.contains("[paused: wait_user]"));
        Assert.assertTrue(resumePrompt.contains("Human: 同意，继续"));
        AiTokenUsage usage = tokenUsage(result);
        Assert.assertNotNull(usage);
        Assert.assertFalse(usageEvents.isEmpty());
        AgentExecutionMetrics metrics = executionMetrics(result);
        Assert.assertNotNull(metrics);
        System.out.println("[AgentExecutor resume tokenUsage] " + usage);
        System.out.println("[AgentExecutor resume executionMetrics] " + metrics);
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
