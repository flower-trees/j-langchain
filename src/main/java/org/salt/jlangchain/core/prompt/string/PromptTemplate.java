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

package org.salt.jlangchain.core.prompt.string;

import lombok.Builder;
import org.apache.commons.text.StringSubstitutor;
import org.salt.jlangchain.core.BaseRunnable;
import org.salt.jlangchain.core.prompt.value.StringPromptValue;
import org.salt.jlangchain.utils.GroceryUtil;
import org.salt.jlangchain.utils.JsonUtil;

import java.util.Map;

@Builder
public class PromptTemplate extends StringPromptTemplate {

    private String template;

    @Override
    public StringPromptValue invoke(Object input) {
        if (input != null) {
            if (input instanceof Map) {
                StringSubstitutor sub = new StringSubstitutor((Map<String, ?>)input);
                return StringPromptValue.builder().text(sub.replace(template)).build();
            } else if (GroceryUtil.isPlainObject(input)) {
                StringSubstitutor sub = new StringSubstitutor(JsonUtil.toMap(input));
                return StringPromptValue.builder().text(sub.replace(template)).build();
            }
        }

        throw new RuntimeException("input must be Map or PlainObject");
    }

    public static BaseRunnable<StringPromptValue, Object> fromTemplate(String template) {
        return PromptTemplate.builder().template(template).build();
    }
}
