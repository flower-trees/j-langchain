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

package org.salt.jlangchain.rag.embedding;

import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.SuperBuilder;
import org.salt.jlangchain.ai.chat.strategy.AiChatActuator;
import org.salt.jlangchain.ai.vendor.chatgpt.ChatGPTActuator;

@EqualsAndHashCode(callSuper = true)
@Data
@SuperBuilder
public class OpenAIEmbeddings extends Embeddings {

    @Builder.Default
    protected String model = "text-embedding-ada-002";
    @Builder.Default
    protected int vectorSize = 1536;

    @Override
    public Class<? extends AiChatActuator> getActuator() {
        return ChatGPTActuator.class;
    }
}