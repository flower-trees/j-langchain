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

package org.salt.jlangchain.core.prompt.chat;

import lombok.Builder;
import org.apache.commons.text.StringSubstitutor;
import org.salt.jlangchain.core.BaseRunnable;
import org.salt.jlangchain.core.message.HumanMessage;
import org.salt.jlangchain.core.prompt.value.ChatPromptValue;
import org.salt.jlangchain.utils.GroceryUtil;
import org.salt.jlangchain.utils.JsonUtil;

import java.util.List;
import java.util.Map;

@Builder
public class ChatPromptTemplate extends BaseChatPromptTemplate {

    private String template;

    @Override
    public ChatPromptValue invoke(Object input) {

        if (input != null) {
            if (input instanceof Map) {
                StringSubstitutor sub = new StringSubstitutor((Map<String, ?>)input);
                HumanMessage message = HumanMessage.builder().content(sub.replace(template)).build();
                return ChatPromptValue.builder().messages(List.of(message)).build();
            } else if (GroceryUtil.isPlainObject(input)) {
                StringSubstitutor sub = new StringSubstitutor(JsonUtil.toMap(input));
                HumanMessage message = HumanMessage.builder().content(sub.replace(template)).build();
                return ChatPromptValue.builder().messages(List.of(message)).build();
            }
        }

        throw new RuntimeException("input must be Map or PlainObject");
    }

    public static BaseRunnable<ChatPromptValue, Object> fromTemplate(String template) {
        return ChatPromptTemplate.builder().template(template).build();
    }
}
