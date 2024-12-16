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

package org.salt.jlangchain.core.parser;

import org.salt.jlangchain.core.BaseRunnable;
import org.salt.jlangchain.core.parser.generation.Generation;

import java.util.List;

public abstract class BaseLLMOutputParser<O> extends BaseRunnable<O, Object> {
    protected abstract O parseResult(List<Generation> result);
}
