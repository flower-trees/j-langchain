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

import lombok.Builder;
import lombok.Data;
import org.salt.jlangchain.ai.common.param.AiTokenUsage;

/**
 * Emitted after each LLM call in an agent loop.
 */
@Data
@Builder
public class AgentTokenUsageEvent {

    private String taskId;
    private AiTokenUsage deltaUsage;
    private AiTokenUsage totalUsage;
    private long llmCalls;
    private long toolCalls;
    private long deltaDurationMs;
    private long totalDurationMs;
    private long llmDurationMs;
    private long toolDurationMs;
}
