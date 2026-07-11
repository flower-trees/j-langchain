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
 * A single {@code references/*.md} document loaded for a skill.
 *
 * @param filename file name including extension (e.g. "destinations.md")
 * @param content  full file content
 * @param summary  first non-blank line of the file, used as a one-line manifest
 *                 entry when {@link ReferencesMode#LAZY} is active; may be blank
 */
public record ReferenceDoc(String filename, String content, String summary) {
}
