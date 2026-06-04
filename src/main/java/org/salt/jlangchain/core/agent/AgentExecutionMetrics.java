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

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Aggregate execution timing for one agent task.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class AgentExecutionMetrics {

    public static final String METADATA_KEY = "executionMetrics";

    private long startedAtMs;
    private long endedAtMs;
    private long durationMs;
    private long llmDurationMs;
    private long toolDurationMs;
    private long llmCalls;
    private long toolCalls;
}
