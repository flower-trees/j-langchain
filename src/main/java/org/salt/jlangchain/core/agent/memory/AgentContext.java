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

import org.salt.jlangchain.core.agent.AgentExecutor;
import org.salt.jlangchain.core.agent.McpAgentExecutor;

/**
 * Factory for per-invocation {@link AgentTaskContext} instances.
 *
 * <p>Inject a configured implementation into {@link AgentExecutor} or
 * {@link McpAgentExecutor} via the builder's {@code context()} method.
 * {@link #create} is called once at the start of each {@code invoke()} call.
 *
 * <p>Built-in implementations:
 * <ul>
 *   <li>{@link SlidingWindowContext} — sliding-window compression with optional LLM summarizer</li>
 *   <li>{@link FullContext} — no compression, full history accumulation (default)</li>
 * </ul>
 */
public interface AgentContext {

    /**
     * Create a fresh {@link AgentTaskContext} for one agent invocation.
     *
     * @param question     the original user question / task
     * @param systemPrompt optional system prompt (may be null)
     */
    AgentTaskContext create(String question, String systemPrompt);
}
