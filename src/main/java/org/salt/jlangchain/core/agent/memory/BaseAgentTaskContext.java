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

package org.salt.jlangchain.core.agent.memory;

import org.salt.jlangchain.ai.common.param.AiTokenUsage;
import org.salt.jlangchain.core.agent.AgentExecutionMetrics;

import java.util.UUID;

/**
 * Shared per-task accounting for agent contexts.
 */
public abstract class BaseAgentTaskContext implements AgentTaskContext {

    private final String taskId = UUID.randomUUID().toString();
    private final AiTokenUsage tokenUsage = AiTokenUsage.empty();
    private final long startedAtMs = System.currentTimeMillis();
    private long endedAtMs;
    private long llmDurationMs;
    private long toolDurationMs;

    @Override
    public String getTaskId() {
        return taskId;
    }

    @Override
    public void addTokenUsage(AiTokenUsage usage) {
        tokenUsage.add(usage);
    }

    @Override
    public void addToolCalls(long count) {
        tokenUsage.addToolCalls(count);
    }

    @Override
    public void addLlmDuration(long durationMs) {
        llmDurationMs += Math.max(0, durationMs);
    }

    @Override
    public void addToolDuration(long durationMs) {
        toolDurationMs += Math.max(0, durationMs);
    }

    @Override
    public void markEnded() {
        endedAtMs = System.currentTimeMillis();
    }

    @Override
    public AiTokenUsage getTokenUsage() {
        return tokenUsage.copy();
    }

    @Override
    public AgentExecutionMetrics getExecutionMetrics() {
        long end = endedAtMs > 0 ? endedAtMs : System.currentTimeMillis();
        return new AgentExecutionMetrics(startedAtMs, endedAtMs, end - startedAtMs,
                llmDurationMs, toolDurationMs, tokenUsage.getLlmCalls(), tokenUsage.getToolCalls());
    }

    protected void reopenForResume() {
        endedAtMs = 0;
    }
}
