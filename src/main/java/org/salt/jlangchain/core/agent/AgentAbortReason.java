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

/**
 * Reason codes for {@link AgentAbortException} (system-decided stops).
 */
public enum AgentAbortReason {

    /** Loop exceeded the configured {@code maxIterations}. */
    MAX_STEPS,

    /** Loop exceeded the configured {@code maxDurationSeconds}. */
    TIMEOUT,

    /** The LLM kept calling failing tools for too many consecutive rounds. */
    CONSECUTIVE_TOOL_FAILURES,

    /** Token or cost budget was exceeded. */
    BUDGET_EXCEEDED
}
