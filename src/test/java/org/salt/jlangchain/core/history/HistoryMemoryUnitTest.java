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

package org.salt.jlangchain.core.history;

import com.fasterxml.jackson.core.type.TypeReference;
import org.junit.Assert;
import org.junit.Test;
import org.salt.jlangchain.core.history.storage.InMemoryConversationStorage;
import org.salt.jlangchain.core.agent.storage.InMemoryAgentTaskStorage;
import org.salt.jlangchain.core.message.*;
import org.salt.jlangchain.utils.JsonUtil;

import java.util.List;

/**
 * Pure unit tests for the history layer.
 * No Spring context, no LLM calls — all assertions are structural and deterministic.
 */
public class HistoryMemoryUnitTest {

    // ── HistoryInfos model ────────────────────────────────────────────────────

    @Test
    public void testHistoryInfosAutoGeneratesIdAndTimestamp() {
        HistoryInfos h = new HistoryInfos();
        Assert.assertNotNull("id must be auto-generated", h.getId());
        Assert.assertFalse("id must not be blank", h.getId().isBlank());
        Assert.assertTrue("createdAt must be positive", h.getCreatedAt() > 0);
        Assert.assertNull("parentId must be null by default", h.getParentId());
        Assert.assertEquals(HistoryInfos.Type.NORMAL, h.getType());
    }

    @Test
    public void testHistoryInfosBuilderOverridesDefaults() {
        HistoryInfos h = HistoryInfos.builder()
                .id("custom-id")
                .parentId("parent-001")
                .createdAt(1_000_000L)
                .type(HistoryInfos.Type.AGENT_STEP)
                .messages(List.of(HumanMessage.builder().content("hi").build()))
                .build();

        Assert.assertEquals("custom-id", h.getId());
        Assert.assertEquals("parent-001", h.getParentId());
        Assert.assertEquals(1_000_000L, h.getCreatedAt());
        Assert.assertEquals(HistoryInfos.Type.AGENT_STEP, h.getType());
        Assert.assertEquals(1, h.getMessages().size());
        Assert.assertEquals("hi", h.getMessages().get(0).getContent());
    }

    @Test
    public void testHistoryInfosBuilderAutoFillsIdAndTimestampWhenAbsent() {
        HistoryInfos h = HistoryInfos.builder()
                .type(HistoryInfos.Type.SUMMARY)
                .messages(List.of())
                .build();

        Assert.assertNotNull(h.getId());
        Assert.assertFalse(h.getId().isBlank());
        Assert.assertTrue(h.getCreatedAt() > 0);
    }

    @Test
    public void testHistoryInfosTypesExist() {
        Assert.assertNotNull(HistoryInfos.Type.valueOf("NORMAL"));
        Assert.assertNotNull(HistoryInfos.Type.valueOf("SUMMARY"));
        Assert.assertNotNull(HistoryInfos.Type.valueOf("AGENT_STEP"));
        Assert.assertNotNull(HistoryInfos.Type.valueOf("TASK_SUMMARY"));
    }

    @Test
    public void testTwoHistoryInfosHaveDistinctIds() {
        HistoryInfos a = new HistoryInfos();
        HistoryInfos b = new HistoryInfos();
        Assert.assertNotEquals("each HistoryInfos must have a unique id", a.getId(), b.getId());
    }

    // ── InMemoryConversationStorage ───────────────────────────────────────────

    @Test
    public void testConversationStorageEmptyOnFirstLoad() {
        InMemoryConversationStorage storage = new InMemoryConversationStorage();
        List<HistoryInfos> result = storage.loadAll(0L, 1L, 1L);
        Assert.assertTrue("fresh session must be empty", result.isEmpty());
    }

    @Test
    public void testConversationStorageAppendIncreasesSize() {
        InMemoryConversationStorage storage = new InMemoryConversationStorage();
        Long appId = 0L, userId = 1L, sessionId = 1L;

        storage.append(appId, userId, sessionId, turn("hello", "hi"));
        Assert.assertEquals(1, storage.loadAll(appId, userId, sessionId).size());

        storage.append(appId, userId, sessionId, turn("name?", "Todd"));
        Assert.assertEquals(2, storage.loadAll(appId, userId, sessionId).size());
    }

    @Test
    public void testConversationStorageReplaceIsAtomic() {
        InMemoryConversationStorage storage = new InMemoryConversationStorage();
        Long appId = 0L, userId = 1L, sessionId = 1L;

        storage.append(appId, userId, sessionId, turn("a", "1"));
        storage.append(appId, userId, sessionId, turn("b", "2"));
        storage.append(appId, userId, sessionId, turn("c", "3"));

        HistoryInfos summary = HistoryInfos.builder()
                .type(HistoryInfos.Type.SUMMARY)
                .messages(List.of(SystemMessage.builder().content("summary text").build()))
                .build();
        storage.replace(appId, userId, sessionId, List.of(summary));

        List<HistoryInfos> after = storage.loadAll(appId, userId, sessionId);
        Assert.assertEquals(1, after.size());
        Assert.assertEquals(HistoryInfos.Type.SUMMARY, after.get(0).getType());
    }

    @Test
    public void testConversationStorageClearEmptiesSession() {
        InMemoryConversationStorage storage = new InMemoryConversationStorage();
        Long appId = 0L, userId = 1L, sessionId = 1L;

        storage.append(appId, userId, sessionId, turn("x", "y"));
        storage.append(appId, userId, sessionId, turn("x", "y"));
        storage.clear(appId, userId, sessionId);

        Assert.assertTrue(storage.loadAll(appId, userId, sessionId).isEmpty());
    }

    @Test
    public void testConversationStorageSessionIsolation() {
        InMemoryConversationStorage storage = new InMemoryConversationStorage();
        Long appId = 0L, userId = 1L;

        storage.append(appId, userId, 10L, turn("s10", "r10"));
        storage.append(appId, userId, 20L, turn("s20a", "r20a"));
        storage.append(appId, userId, 20L, turn("s20b", "r20b"));

        Assert.assertEquals(1, storage.loadAll(appId, userId, 10L).size());
        Assert.assertEquals(2, storage.loadAll(appId, userId, 20L).size());
        Assert.assertTrue(storage.loadAll(appId, userId, 99L).isEmpty());
    }

    @Test
    public void testConversationStorageUserIsolation() {
        InMemoryConversationStorage storage = new InMemoryConversationStorage();
        Long appId = 0L, sessionId = 1L;

        storage.append(appId, 100L, sessionId, turn("u100", "r"));
        storage.append(appId, 200L, sessionId, turn("u200a", "r"));
        storage.append(appId, 200L, sessionId, turn("u200b", "r"));

        Assert.assertEquals(1, storage.loadAll(appId, 100L, sessionId).size());
        Assert.assertEquals(2, storage.loadAll(appId, 200L, sessionId).size());
    }

    @Test
    public void testConversationStorageClearDoesNotAffectOtherSessions() {
        InMemoryConversationStorage storage = new InMemoryConversationStorage();
        Long appId = 0L, userId = 1L;

        storage.append(appId, userId, 1L, turn("a", "b"));
        storage.append(appId, userId, 2L, turn("c", "d"));
        storage.clear(appId, userId, 1L);

        Assert.assertTrue(storage.loadAll(appId, userId, 1L).isEmpty());
        Assert.assertEquals(1, storage.loadAll(appId, userId, 2L).size());
    }

    @Test
    public void testConversationStorageLoadAllReturnsCopy() {
        InMemoryConversationStorage storage = new InMemoryConversationStorage();
        Long appId = 0L, userId = 1L, sessionId = 1L;
        storage.append(appId, userId, sessionId, turn("a", "b"));

        List<HistoryInfos> list1 = storage.loadAll(appId, userId, sessionId);
        List<HistoryInfos> list2 = storage.loadAll(appId, userId, sessionId);
        Assert.assertNotSame("loadAll must return a defensive copy", list1, list2);
    }

    // ── InMemoryAgentTaskStorage ──────────────────────────────────────────────

    @Test
    public void testAgentTaskStorageEmptyOnFirstLoad() {
        InMemoryAgentTaskStorage storage = new InMemoryAgentTaskStorage();
        Assert.assertTrue(storage.loadByTaskId("non-existent").isEmpty());
    }

    @Test
    public void testAgentTaskStorageAppendAndLoad() {
        InMemoryAgentTaskStorage storage = new InMemoryAgentTaskStorage();
        String taskId = "task-001";

        storage.append(taskId, agentStep(taskId));
        storage.append(taskId, agentStep(taskId));

        List<HistoryInfos> loaded = storage.loadByTaskId(taskId);
        Assert.assertEquals(2, loaded.size());
        Assert.assertTrue(loaded.stream().allMatch(h -> taskId.equals(h.getParentId())));
    }

    @Test
    public void testAgentTaskStorageTaskIsolation() {
        InMemoryAgentTaskStorage storage = new InMemoryAgentTaskStorage();

        storage.append("task-A", agentStep("task-A"));
        storage.append("task-B", agentStep("task-B"));
        storage.append("task-B", agentStep("task-B"));

        Assert.assertEquals(1, storage.loadByTaskId("task-A").size());
        Assert.assertEquals(2, storage.loadByTaskId("task-B").size());
    }

    @Test
    public void testAgentTaskStorageReplaceWithTaskSummary() {
        InMemoryAgentTaskStorage storage = new InMemoryAgentTaskStorage();
        String taskId = "task-compress";

        storage.append(taskId, agentStep(taskId));
        storage.append(taskId, agentStep(taskId));
        storage.append(taskId, agentStep(taskId));

        HistoryInfos summary = HistoryInfos.builder()
                .type(HistoryInfos.Type.TASK_SUMMARY)
                .parentId(taskId)
                .messages(List.of(AIMessage.builder().content("summary of steps 1-2").build()))
                .build();
        HistoryInfos remaining = agentStep(taskId);
        storage.replace(taskId, List.of(summary, remaining));

        List<HistoryInfos> after = storage.loadByTaskId(taskId);
        Assert.assertEquals(2, after.size());
        Assert.assertEquals(HistoryInfos.Type.TASK_SUMMARY, after.get(0).getType());
        Assert.assertEquals(HistoryInfos.Type.AGENT_STEP, after.get(1).getType());
    }

    @Test
    public void testAgentTaskStorageClear() {
        InMemoryAgentTaskStorage storage = new InMemoryAgentTaskStorage();
        String taskId = "task-clear";
        storage.append(taskId, agentStep(taskId));
        storage.append(taskId, agentStep(taskId));

        storage.clear(taskId);
        Assert.assertTrue(storage.loadByTaskId(taskId).isEmpty());
    }

    @Test
    public void testAgentTaskStorageClearDoesNotAffectOtherTasks() {
        InMemoryAgentTaskStorage storage = new InMemoryAgentTaskStorage();
        storage.append("task-A", agentStep("task-A"));
        storage.append("task-B", agentStep("task-B"));

        storage.clear("task-A");
        Assert.assertTrue(storage.loadByTaskId("task-A").isEmpty());
        Assert.assertEquals(1, storage.loadByTaskId("task-B").size());
    }

    @Test
    public void testAgentTaskStorageLoadReturnsCopy() {
        InMemoryAgentTaskStorage storage = new InMemoryAgentTaskStorage();
        storage.append("t", agentStep("t"));

        List<HistoryInfos> l1 = storage.loadByTaskId("t");
        List<HistoryInfos> l2 = storage.loadByTaskId("t");
        Assert.assertNotSame("loadByTaskId must return a defensive copy", l1, l2);
    }

    // ── BaseMessage Jackson serialization ─────────────────────────────────────

    /**
     * Serialization must embed the correct role field so polymorphic deserialization
     * can work when a full Jackson creator (no-arg constructor / @JsonCreator) is added later.
     */
    @Test
    public void testBaseMessageSerializationIncludesRoleField() throws Exception {
        String humanJson  = JsonUtil.objectMapper.writeValueAsString(HumanMessage.builder().content("hello").build());
        String aiJson     = JsonUtil.objectMapper.writeValueAsString(AIMessage.builder().content("hi").build());
        String systemJson = JsonUtil.objectMapper.writeValueAsString(SystemMessage.builder().content("sys").build());
        String toolJson   = JsonUtil.objectMapper.writeValueAsString(
                ToolMessage.builder().content("r").name("t").toolCallId("tc1").build());

        Assert.assertTrue("HumanMessage JSON must contain role=human",  humanJson.contains("\"human\""));
        Assert.assertTrue("AIMessage JSON must contain role=ai",         aiJson.contains("\"ai\""));
        Assert.assertTrue("SystemMessage JSON must contain role=system", systemJson.contains("\"system\""));
        Assert.assertTrue("ToolMessage JSON must contain role=tool",     toolJson.contains("\"tool\""));
    }

    @Test
    public void testBaseMessageSerializationPreservesContent() throws Exception {
        String json = JsonUtil.objectMapper.writeValueAsString(
                HumanMessage.builder().content("important text").build());
        Assert.assertTrue(json.contains("important text"));
    }

    @Test
    public void testToolMessageSerializationIncludesNameAndToolCallId() throws Exception {
        String json = JsonUtil.objectMapper.writeValueAsString(
                ToolMessage.builder().content("result").name("get_weather").toolCallId("tc-42").build());
        Assert.assertTrue(json.contains("get_weather"));
        Assert.assertTrue(json.contains("tc-42"));
    }

    @Test
    public void testHistoryInfosSerializationIncludesAllFields() throws Exception {
        HistoryInfos h = HistoryInfos.builder()
                .type(HistoryInfos.Type.NORMAL)
                .parentId("parent-xyz")
                .messages(List.of(
                        HumanMessage.builder().content("what is the weather?").build(),
                        AIMessage.builder().content("it is sunny").build()
                ))
                .build();

        String json = JsonUtil.objectMapper.writeValueAsString(h);
        Assert.assertNotNull(json);
        Assert.assertTrue("JSON must include type",     json.contains("NORMAL"));
        Assert.assertTrue("JSON must include parentId", json.contains("parent-xyz"));
        Assert.assertTrue("JSON must include messages", json.contains("what is the weather?"));
        Assert.assertTrue("JSON must include ai reply", json.contains("it is sunny"));
        Assert.assertTrue("JSON must include role fields for polymorphic deserialization",
                json.contains("\"human\"") && json.contains("\"ai\""));
    }

    @Test
    public void testHistoryInfosSerializationProducesValidJson() throws Exception {
        HistoryInfos original = HistoryInfos.builder()
                .type(HistoryInfos.Type.AGENT_STEP)
                .parentId("task-001")
                .messages(List.of(
                        AIMessage.builder().content("AI response").build()
                ))
                .build();

        String json = JsonUtil.toJson(original);
        Assert.assertNotNull(json);
        Assert.assertTrue("JSON must contain type field",    json.contains("AGENT_STEP"));
        Assert.assertTrue("JSON must contain parentId",      json.contains("task-001"));
        Assert.assertTrue("JSON must contain message content", json.contains("AI response"));
        Assert.assertTrue("JSON must include role for polymorphism", json.contains("\"ai\""));
        // Verify the produced JSON is valid JSON structure
        Assert.assertTrue(JsonUtil.isValidJson(json));
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static HistoryInfos turn(String humanText, String aiText) {
        return HistoryInfos.builder()
                .type(HistoryInfos.Type.NORMAL)
                .messages(List.of(
                        HumanMessage.builder().content(humanText).build(),
                        AIMessage.builder().content(aiText).build()
                ))
                .build();
    }

    private static HistoryInfos agentStep(String parentId) {
        return HistoryInfos.builder()
                .type(HistoryInfos.Type.AGENT_STEP)
                .parentId(parentId)
                .messages(List.of(AIMessage.builder().content("tool result").build()))
                .build();
    }
}
