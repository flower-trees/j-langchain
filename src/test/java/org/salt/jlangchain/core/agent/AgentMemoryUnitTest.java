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
import org.salt.jlangchain.core.agent.memory.*;
import org.salt.jlangchain.core.agent.storage.InMemoryAgentTaskStorage;
import org.salt.jlangchain.core.history.HistoryInfos;
import org.salt.jlangchain.core.message.*;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.stream.Collectors;

/**
 * Pure unit tests for the agent memory layer.
 * No Spring context, no LLM calls — all assertions are structural and deterministic.
 */
public class AgentMemoryUnitTest {

    // ── AgentStep ─────────────────────────────────────────────────────────────

    @Test
    public void testAgentStepFunctionCallFields() {
        AIMessage aiMsg = AIMessage.builder().content("I will call get_weather").build();
        ToolMessage toolResult = ToolMessage.builder()
                .content("sunny, 25°C").name("get_weather").toolCallId("tc-1").build();

        AgentStep step = AgentStep.ofFunctionCall(aiMsg, List.of(toolResult));

        Assert.assertNotNull(step.getAiMessage());
        Assert.assertEquals("I will call get_weather", step.getAiMessage().getContent());
        Assert.assertEquals(1, step.getToolResults().size());
        Assert.assertEquals("sunny, 25°C", step.getToolResults().get(0).getContent());
        Assert.assertNull(step.getScratchpadText());
    }

    @Test
    public void testAgentStepFunctionCallNullAiContentFixedToEmpty() {
        AIMessage aiMsg = AIMessage.builder().build(); // content is null
        Assert.assertNull(aiMsg.getContent());

        AgentStep step = AgentStep.ofFunctionCall(aiMsg, List.of());
        Assert.assertEquals("null content must be fixed to empty string", "", step.getAiMessage().getContent());
    }

    @Test
    public void testAgentStepFunctionCallNullToolResultsBecomesEmptyList() {
        AIMessage aiMsg = AIMessage.builder().content("hi").build();
        AgentStep step = AgentStep.ofFunctionCall(aiMsg, null);
        Assert.assertNotNull(step.getToolResults());
        Assert.assertTrue(step.getToolResults().isEmpty());
    }

    @Test
    public void testAgentStepReActFields() {
        AgentStep step = AgentStep.ofReAct("Thought: I need weather\nAction: get_weather\nObservation: sunny");

        Assert.assertEquals("Thought: I need weather\nAction: get_weather\nObservation: sunny", step.getScratchpadText());
        Assert.assertNull(step.getAiMessage());
        Assert.assertNotNull(step.getToolResults());
        Assert.assertTrue(step.getToolResults().isEmpty());
    }

    @Test
    public void testAgentStepToMessagesForFunctionCall() {
        AIMessage aiMsg = AIMessage.builder().content("calling tool").build();
        ToolMessage toolResult = ToolMessage.builder().content("result").name("tool").toolCallId("tc").build();

        AgentStep step = AgentStep.ofFunctionCall(aiMsg, List.of(toolResult));
        List<BaseMessage> msgs = step.toMessages();

        Assert.assertEquals(2, msgs.size());
        Assert.assertSame(aiMsg, msgs.get(0));
        Assert.assertSame(toolResult, msgs.get(1));
    }

    @Test
    public void testAgentStepToMessagesForReActIsEmpty() {
        AgentStep step = AgentStep.ofReAct("Thought: ...\nAction: ...");
        Assert.assertTrue("ReAct steps produce no messages (they use raw text)", step.toMessages().isEmpty());
    }

    @Test
    public void testAgentStepToMessagesForFunctionCallWithMultipleToolResults() {
        AIMessage aiMsg = AIMessage.builder().content("multi-tool").build();
        List<BaseMessage> results = List.of(
                ToolMessage.builder().content("r1").name("t1").toolCallId("tc1").build(),
                ToolMessage.builder().content("r2").name("t2").toolCallId("tc2").build()
        );

        AgentStep step = AgentStep.ofFunctionCall(aiMsg, results);
        List<BaseMessage> msgs = step.toMessages();

        Assert.assertEquals(3, msgs.size());
        Assert.assertEquals("r1", msgs.get(1).getContent());
        Assert.assertEquals("r2", msgs.get(2).getContent());
    }

    // ── FullContext ───────────────────────────────────────────────────────────

    @Test
    public void testFullContextCreateReturnsUniqueTaskIds() {
        FullContext ctx = FullContext.build();
        AgentTaskContext s1 = ctx.create("q1", null);
        AgentTaskContext s2 = ctx.create("q2", null);
        Assert.assertNotEquals("each create() call must return a fresh session with unique taskId",
                s1.getTaskId(), s2.getTaskId());
    }

    @Test
    public void testFullContextBuildMessagesNoSystemPromptNoSteps() {
        AgentTaskContext session = FullContext.build().create("What is 1+1?", null);
        List<BaseMessage> msgs = session.buildMessages();

        Assert.assertEquals(1, msgs.size());
        Assert.assertTrue(msgs.get(0) instanceof HumanMessage);
        Assert.assertEquals("What is 1+1?", msgs.get(0).getContent());
    }

    @Test
    public void testFullContextBuildMessagesWithSystemPrompt() {
        AgentTaskContext session = FullContext.build().create("Hello", "You are an assistant.");
        List<BaseMessage> msgs = session.buildMessages();

        Assert.assertEquals(2, msgs.size());
        Assert.assertTrue(msgs.get(0) instanceof SystemMessage);
        Assert.assertEquals("You are an assistant.", msgs.get(0).getContent());
        Assert.assertTrue(msgs.get(1) instanceof HumanMessage);
    }

    @Test
    public void testFullContextBuildMessagesAccumulatesAllSteps() {
        AgentTaskContext session = FullContext.build().create("question", null);

        // step 1: AI + tool result
        AIMessage ai1 = AIMessage.builder().content("calling weather").build();
        ToolMessage tr1 = ToolMessage.builder().content("sunny").name("weather").toolCallId("t1").build();
        session.addStep(AgentStep.ofFunctionCall(ai1, List.of(tr1)));

        // step 2: AI + tool result
        AIMessage ai2 = AIMessage.builder().content("calling time").build();
        ToolMessage tr2 = ToolMessage.builder().content("14:30").name("time").toolCallId("t2").build();
        session.addStep(AgentStep.ofFunctionCall(ai2, List.of(tr2)));

        List<BaseMessage> msgs = session.buildMessages();
        // [HumanMessage, AI1, Tool1, AI2, Tool2]
        Assert.assertEquals(5, msgs.size());
        Assert.assertTrue(msgs.get(0) instanceof HumanMessage);
        Assert.assertEquals("calling weather", msgs.get(1).getContent());
        Assert.assertEquals("sunny", msgs.get(2).getContent());
        Assert.assertEquals("calling time", msgs.get(3).getContent());
        Assert.assertEquals("14:30", msgs.get(4).getContent());
    }

    @Test
    public void testFullContextReActBasePromptTextSetOnlyOnce() {
        AgentTaskContext session = FullContext.build().create("question", null);
        session.initReactBasePromptText("base prompt text");
        session.initReactBasePromptText("second call — must be ignored");

        String result = session.buildReactPromptText();
        Assert.assertTrue(result.startsWith("base prompt text"));
        Assert.assertFalse("second initReactBasePromptText call must be a no-op",
                result.contains("second call — must be ignored"));
    }

    @Test
    public void testFullContextBuildReactPromptTextAppendsAllScratchpads() {
        AgentTaskContext session = FullContext.build().create("question", null);
        session.initReactBasePromptText("BASE\n");
        session.addStep(AgentStep.ofReAct("Thought: think1\nObservation: obs1\n"));
        session.addStep(AgentStep.ofReAct("Thought: think2\nObservation: obs2\n"));

        String text = session.buildReactPromptText();
        Assert.assertTrue(text.startsWith("BASE\n"));
        Assert.assertTrue(text.contains("think1"));
        Assert.assertTrue(text.contains("obs1"));
        Assert.assertTrue(text.contains("think2"));
        Assert.assertTrue(text.contains("obs2"));
    }

    @Test
    public void testFullContextBuildReactPromptTextEmptyWhenBaseNotSet() {
        AgentTaskContext session = FullContext.build().create("question", null);
        session.addStep(AgentStep.ofReAct("Thought: x"));

        Assert.assertEquals("", session.buildReactPromptText());
    }

    @Test
    public void testFullContextBuildChatPromptValueWrapsMessages() {
        AgentTaskContext session = FullContext.build().create("Hi", "System prompt");
        var promptValue = session.buildChatPromptValue();

        Assert.assertNotNull(promptValue);
        Assert.assertEquals(2, promptValue.getMessages().size());
    }

    // ── SlidingWindowContext — window enforcement ──────────────────────────────

    @Test
    public void testSlidingWindowWindowSize1CompressesOldestStep() {
        SlidingWindowContext ctx = SlidingWindowContext.builder().windowSize(1).build();
        AgentTaskContext session = ctx.create("question", null);

        session.initReactBasePromptText("BASE\n");
        session.addStep(AgentStep.ofReAct("Thought: step1\n"));  // occupies the window
        session.addStep(AgentStep.ofReAct("Thought: step2\n"));  // pushes step1 out

        String text = session.buildReactPromptText();
        // step1 is now in earlyStepsSummary, prefixed with "[Earlier steps summary]"
        Assert.assertTrue("compressed step must appear in early summary",
                text.contains("[Earlier steps summary]"));
        Assert.assertTrue("step1 content must be preserved in summary", text.contains("step1"));
        Assert.assertTrue("step2 must still be in the window", text.contains("step2"));
    }

    @Test
    public void testSlidingWindowWindowSize2CompressesOnThirdStep() {
        SlidingWindowContext ctx = SlidingWindowContext.builder().windowSize(2).build();
        AgentTaskContext session = ctx.create("question", null);
        session.initReactBasePromptText("BASE\n");

        session.addStep(AgentStep.ofReAct("Thought: step1\n"));
        session.addStep(AgentStep.ofReAct("Thought: step2\n"));
        // No compression yet — window has 2 slots and both are filled
        String noCompression = session.buildReactPromptText();
        Assert.assertFalse("no compression yet with windowSize=2", noCompression.contains("[Earlier steps summary]"));

        session.addStep(AgentStep.ofReAct("Thought: step3\n"));
        // Now step1 is compressed
        String withCompression = session.buildReactPromptText();
        Assert.assertTrue(withCompression.contains("[Earlier steps summary]"));
        Assert.assertTrue("step1 in summary", withCompression.contains("step1"));
        Assert.assertTrue("step2 still in window", withCompression.contains("step2"));
        Assert.assertTrue("step3 still in window", withCompression.contains("step3"));
    }

    @Test
    public void testSlidingWindowEarlyStepsSummaryAppearsInSystemMessage() {
        SlidingWindowContext ctx = SlidingWindowContext.builder().windowSize(1).build();
        AgentTaskContext session = ctx.create("question", null);

        // Function-calling mode: use AI + tool messages so stepToText reads from messages
        AIMessage ai1 = AIMessage.builder().content("step1 AI msg").build();
        ToolMessage tr1 = ToolMessage.builder().content("step1 tool result").name("t").toolCallId("tc1").build();
        session.addStep(AgentStep.ofFunctionCall(ai1, List.of(tr1)));

        AIMessage ai2 = AIMessage.builder().content("step2 AI msg").build();
        ToolMessage tr2 = ToolMessage.builder().content("step2 tool result").name("t").toolCallId("tc2").build();
        session.addStep(AgentStep.ofFunctionCall(ai2, List.of(tr2)));

        List<BaseMessage> msgs = session.buildMessages();
        // First message must be SystemMessage containing the earlyStepsSummary (step1)
        Assert.assertFalse("messages must not be empty after compression", msgs.isEmpty());
        Assert.assertTrue("first message must be SystemMessage with summary",
                msgs.get(0) instanceof SystemMessage);

        String sysContent = msgs.get(0).getContent();
        Assert.assertTrue("system message must contain step1 info", sysContent.contains("step1"));
    }

    @Test
    public void testSlidingWindowEarlyStepsSummaryMergesAcrossMultipleCompressions() {
        SlidingWindowContext ctx = SlidingWindowContext.builder().windowSize(1).build();
        AgentTaskContext session = ctx.create("question", null);
        session.initReactBasePromptText("BASE\n");

        session.addStep(AgentStep.ofReAct("step1 text\n"));
        session.addStep(AgentStep.ofReAct("step2 text\n")); // compresses step1
        session.addStep(AgentStep.ofReAct("step3 text\n")); // compresses step2

        String text = session.buildReactPromptText();
        // earlyStepsSummary = concat(step1, step2) = "step1 text\nstep2 text"
        Assert.assertTrue(text.contains("step1"));
        Assert.assertTrue(text.contains("step2"));
        Assert.assertTrue(text.contains("step3"));
    }

    @Test
    public void testSlidingWindowSystemPromptAndSummaryAreCombined() {
        SlidingWindowContext ctx = SlidingWindowContext.builder().windowSize(1).build();
        AgentTaskContext session = ctx.create("question", "You are an assistant.");

        AIMessage ai1 = AIMessage.builder().content("ai response 1").build();
        ToolMessage tr1 = ToolMessage.builder().content("tool result 1").name("t").toolCallId("tc1").build();
        session.addStep(AgentStep.ofFunctionCall(ai1, List.of(tr1)));

        AIMessage ai2 = AIMessage.builder().content("ai response 2").build();
        ToolMessage tr2 = ToolMessage.builder().content("tool result 2").name("t").toolCallId("tc2").build();
        session.addStep(AgentStep.ofFunctionCall(ai2, List.of(tr2)));

        List<BaseMessage> msgs = session.buildMessages();
        String sysContent = msgs.get(0).getContent();
        Assert.assertTrue("system prompt must be present", sysContent.contains("You are an assistant."));
        Assert.assertTrue("early steps summary must be appended to system prompt", sysContent.contains("ai response 1"));
    }

    // ── SlidingWindowContext — AgentTaskStorage integration ───────────────────

    @Test
    public void testSlidingWindowUpdatesAgentTaskStorageOnStep() {
        InMemoryAgentTaskStorage taskStorage = new InMemoryAgentTaskStorage();
        SlidingWindowContext ctx = SlidingWindowContext.builder()
                .windowSize(5)
                .taskStorage(taskStorage)
                .build();
        AgentTaskContext session = ctx.create("question", null);
        String taskId = session.getTaskId();

        AIMessage ai1 = AIMessage.builder().content("step1").build();
        ToolMessage tr1 = ToolMessage.builder().content("r1").name("t").toolCallId("tc1").build();
        session.addStep(AgentStep.ofFunctionCall(ai1, List.of(tr1)));

        List<HistoryInfos> stored = taskStorage.loadByTaskId(taskId);
        Assert.assertEquals(1, stored.size());
        Assert.assertEquals(HistoryInfos.Type.AGENT_STEP, stored.get(0).getType());
        Assert.assertEquals(taskId, stored.get(0).getParentId());
    }

    @Test
    public void testSlidingWindowReplacesWithTaskSummaryOnCompression() {
        InMemoryAgentTaskStorage taskStorage = new InMemoryAgentTaskStorage();
        SlidingWindowContext ctx = SlidingWindowContext.builder()
                .windowSize(1)
                .taskStorage(taskStorage)
                .build();
        AgentTaskContext session = ctx.create("question", null);
        String taskId = session.getTaskId();

        // Step 1 — appended to storage
        AIMessage ai1 = AIMessage.builder().content("step1 ai").build();
        ToolMessage tr1 = ToolMessage.builder().content("step1 result").name("t").toolCallId("tc1").build();
        session.addStep(AgentStep.ofFunctionCall(ai1, List.of(tr1)));

        // Step 2 — triggers compression of step1
        AIMessage ai2 = AIMessage.builder().content("step2 ai").build();
        ToolMessage tr2 = ToolMessage.builder().content("step2 result").name("t").toolCallId("tc2").build();
        session.addStep(AgentStep.ofFunctionCall(ai2, List.of(tr2)));

        List<HistoryInfos> stored = taskStorage.loadByTaskId(taskId);
        Assert.assertEquals("storage must have [TASK_SUMMARY, AGENT_STEP] after compression", 2, stored.size());
        Assert.assertEquals(HistoryInfos.Type.TASK_SUMMARY, stored.get(0).getType());
        Assert.assertEquals(HistoryInfos.Type.AGENT_STEP,   stored.get(1).getType());
    }

    @Test
    public void testSlidingWindowReActStepsNotAppendedToStorage() {
        // ReAct steps produce no messages (toMessages() returns []), so nothing is appended
        InMemoryAgentTaskStorage taskStorage = new InMemoryAgentTaskStorage();
        SlidingWindowContext ctx = SlidingWindowContext.builder()
                .windowSize(5)
                .taskStorage(taskStorage)
                .build();
        AgentTaskContext session = ctx.create("question", null);
        String taskId = session.getTaskId();
        session.initReactBasePromptText("BASE");

        session.addStep(AgentStep.ofReAct("Thought: step1\nObservation: obs1"));
        Assert.assertTrue("ReAct steps must not be written to AgentTaskStorage",
                taskStorage.loadByTaskId(taskId).isEmpty());
    }

    // ── SlidingWindowContext — concurrent session isolation ───────────────────

    @Test
    public void testSlidingWindowConcurrentSessionsAreIsolated() throws InterruptedException {
        SlidingWindowContext ctx = SlidingWindowContext.builder().windowSize(2).build();

        AgentTaskContext s1 = ctx.create("question A", null);
        AgentTaskContext s2 = ctx.create("question B", null);

        Assert.assertNotEquals("concurrent sessions must have distinct task ids",
                s1.getTaskId(), s2.getTaskId());

        s1.addStep(AgentStep.ofReAct("s1 step1"));
        s2.addStep(AgentStep.ofReAct("s2 step1"));
        s2.addStep(AgentStep.ofReAct("s2 step2"));

        // s2 has 2 steps in window; s1 has only 1
        // Their build outputs must not bleed into each other
        s1.initReactBasePromptText("BASE1\n");
        s2.initReactBasePromptText("BASE2\n");

        String text1 = s1.buildReactPromptText();
        String text2 = s2.buildReactPromptText();

        Assert.assertTrue(text1.contains("s1 step1"));
        Assert.assertFalse("s2 content must not appear in s1", text1.contains("s2 step1"));
        Assert.assertTrue(text2.contains("s2 step1"));
        Assert.assertTrue(text2.contains("s2 step2"));
        Assert.assertFalse("s1 content must not appear in s2", text2.contains("s1 step1"));
    }

    @Test
    public void testSlidingWindowParallelSessionsViaThreadPool() throws Exception {
        SlidingWindowContext ctx = SlidingWindowContext.builder().windowSize(1).build();
        ExecutorService pool = Executors.newFixedThreadPool(4);
        int sessions = 8;

        List<Future<String>> futures = new ArrayList<>();
        for (int i = 0; i < sessions; i++) {
            final int idx = i;
            futures.add(pool.submit(() -> {
                AgentTaskContext session = ctx.create("question " + idx, null);
                session.initReactBasePromptText("BASE" + idx + "\n");
                session.addStep(AgentStep.ofReAct("step-A-" + idx + "\n"));
                session.addStep(AgentStep.ofReAct("step-B-" + idx + "\n")); // triggers compression
                return session.buildReactPromptText();
            }));
        }
        pool.shutdown();
        Assert.assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));

        List<String> results = futures.stream()
                .map(f -> { try { return f.get(); } catch (Exception e) { throw new RuntimeException(e); } })
                .collect(Collectors.toList());

        for (int i = 0; i < sessions; i++) {
            String text = results.get(i);
            Assert.assertTrue("session " + i + " must contain its own BASE", text.contains("BASE" + i));
            Assert.assertTrue("session " + i + " must contain step-A in summary", text.contains("step-A-" + i));
            Assert.assertTrue("session " + i + " must contain step-B in window", text.contains("step-B-" + i));
        }
    }

    // ── taskId uniqueness ─────────────────────────────────────────────────────

    @Test
    public void testTaskIdIsUuidFormat() {
        AgentTaskContext session = FullContext.build().create("q", null);
        String taskId = session.getTaskId();
        // UUID format: 8-4-4-4-12 hex chars
        Assert.assertTrue("taskId must match UUID format",
                taskId.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"));
    }
}
