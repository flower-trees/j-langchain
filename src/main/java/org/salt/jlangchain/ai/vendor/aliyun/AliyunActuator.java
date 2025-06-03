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

package org.salt.jlangchain.ai.vendor.aliyun;

import org.salt.jlangchain.ai.chat.openai.OpenAIActuator;
import org.salt.jlangchain.ai.chat.openai.param.OpenAIRequest;
import org.salt.jlangchain.ai.client.stream.HttpStreamClient;
import org.salt.jlangchain.ai.common.param.AiChatInput;
import org.springframework.beans.factory.annotation.Value;

public class AliyunActuator extends OpenAIActuator {

    @Value("${models.aliyun.chat-url:https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions}")
    private String chatUrl;

    @Value("${models.chatgpt.chat-url:https://dashscope.aliyuncs.com/compatible-mode/v1/embeddings}")
    private String embeddingUrl;

    @Value("${models.aliyun.chat-key:${ALIYUN_KEY:}}")
    private String chatKey;

    public AliyunActuator(HttpStreamClient commonHttpClient) {
        super(commonHttpClient);
    }

    @Override
    protected OpenAIRequest convertRequest(AiChatInput aiChatInput) {
        OpenAIRequest openAIRequest = super.convertRequest(aiChatInput);
        openAIRequest.setDimension(String.valueOf(aiChatInput.getVectorSize()));
        return openAIRequest;
    }

    @Override
    protected String getChatUrl() {
        return chatUrl;
    }

    @Override
    protected String getEmbeddingUrl() {
        return embeddingUrl;
    }

    @Override
    protected String getChatKey() {
        return chatKey;
    }
}
