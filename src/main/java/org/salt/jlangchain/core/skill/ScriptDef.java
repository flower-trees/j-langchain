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

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Script definition for a skill.
 * Holds the raw script content so it can be stored in a database
 * and later converted to an executable {@link org.salt.jlangchain.rag.tools.Tool}
 * via {@link ScriptTool#from(ScriptDef)}.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ScriptDef {

    /** Script name without extension, used as the tool name (e.g. "extract_text"). */
    private String name;

    /** File extension that determines the executor: "py", "sh", "js", "rb". */
    private String type;

    /** Full source code of the script. */
    private String content;
}
