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

package org.salt.jlangchain.ai.vendor.doubao;

import org.salt.jlangchain.ai.chat.openai.OpenAIActuator;
import org.salt.jlangchain.ai.client.stream.HttpStreamClient;
import org.springframework.beans.factory.annotation.Value;

public class DoubaoActuator extends OpenAIActuator {

    @Value("${models.doubao.chat-url:https://ark.cn-beijing.volces.com/api/v3/chat/completions}")
    private String chatUrl;

    @Value("${models.doubao.chat-key:${DOUBAO_KEY:}}")
    private String chatKey;

    public DoubaoActuator(HttpStreamClient commonHttpClient) {
        super(commonHttpClient);
    }

    @Override
    protected String getChatUrl() {
        return chatUrl;
    }

    @Override
    protected String getChatKey() {
        return chatKey;
    }
}
