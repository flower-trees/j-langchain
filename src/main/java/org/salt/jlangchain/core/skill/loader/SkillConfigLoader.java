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

package org.salt.jlangchain.core.skill.loader;

import org.salt.jlangchain.core.skill.SkillConfig;

/**
 * Strategy interface for loading a {@link SkillConfig} from any source:
 * classpath, database, HTTP, etc.
 */
public interface SkillConfigLoader {

    /**
     * Load a skill configuration by key.
     * The meaning of {@code key} depends on the implementation
     * (e.g. a classpath directory path or a database record name).
     */
    SkillConfig load(String key);
}
