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

package org.salt.jlangchain.core.skill;

/**
 * Controls how {@code references/*.md} content is exposed to a skill's internal executor.
 *
 * <ul>
 *   <li>{@link #INLINE} — default; full content of every reference doc is concatenated
 *       into the system prompt at build time (original behavior, unchanged).</li>
 *   <li>{@link #LAZY} — only a filename + summary manifest is added to the system prompt;
 *       the internal executor gets a {@code read_reference} tool to fetch full content
 *       on demand. Closer to Claude Code's progressive-disclosure (Level 3) behavior.</li>
 * </ul>
 */
public enum ReferencesMode {
    INLINE,
    LAZY
}
