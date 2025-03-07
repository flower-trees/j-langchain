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
import org.salt.jlangchain.core.prompt.value.StringPromptValue;
import org.salt.jlangchain.rag.tools.Tool;
import org.salt.jlangchain.utils.GroceryUtil;
import org.salt.jlangchain.utils.JsonUtil;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Builder
public class PromptTemplate extends StringPromptTemplate {

    private String template;

    @Override
    public StringPromptValue invoke(Object input) {

        eventAction.eventStart(input, config);
        StringPromptValue result = null;

        try {
            if (input != null) {
                if (input instanceof Map) {
                    StringSubstitutor sub = new StringSubstitutor((Map<String, ?>)input);
                    result = StringPromptValue.builder().text(sub.replace(template)).build();
                    return result;
                } else if (GroceryUtil.isPlainObject(input)) {
                    StringSubstitutor sub = new StringSubstitutor(JsonUtil.toMap(input));
                    result = StringPromptValue.builder().text(sub.replace(template)).build();
                    return result;
                }
            }
            throw new RuntimeException("input must be Map or PlainObject");
        } finally {
            eventAction.eventEnd(result, config);
        }
    }

    public static PromptTemplate fromTemplate(String template) {
        return PromptTemplate.builder().template(template).build();
    }

    public void withTools(List<Tool> tools) {
        String toolInfos = tools.stream().map(tool -> tool.getName() + "(" + tool.getParams() + ") - " + tool.getDescription()).collect(Collectors.joining("\n"));
        String toolNames = tools.stream().map(Tool::getName).collect(Collectors.joining(","));
        StringSubstitutor sub = new StringSubstitutor(Map.of("tools", toolInfos, "toolNames", toolNames));
        template = sub.replace(template);
    }
}
