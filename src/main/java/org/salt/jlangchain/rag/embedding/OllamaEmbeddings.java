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

import org.apache.commons.lang3.StringUtils;
import org.salt.jlangchain.ai.common.param.AiChatInput;
import org.salt.jlangchain.ai.common.param.AiChatOutput;
import org.salt.jlangchain.ai.vendor.ollama.OllamaActuator;
import org.salt.jlangchain.utils.SpringContextUtil;
import org.springframework.util.CollectionUtils;

import java.util.List;

public class OllamaEmbeddings extends Embeddings {
    @Override
    List<List<Double>> embedDocuments(List<String> texts) {

        if (CollectionUtils.isEmpty(texts)) {
            return List.of();
        }

        OllamaActuator actuator = SpringContextUtil.getApplicationContext().getBean(OllamaActuator.class);

        AiChatInput aiChatInput = AiChatInput.builder()
                .model(model)
                .input(texts)
                .build();

        AiChatOutput aiChatOutput = actuator.embedding(aiChatInput);
        if (!CollectionUtils.isEmpty(aiChatOutput.getData())) {
            return aiChatOutput.getData().stream().map(AiChatOutput.DataObject::getEmbedding).toList();
        }

        return List.of();
    }

    @Override
    List<Double> embedQuery(String text) {
        if (StringUtils.isEmpty(text)) {
            return List.of();
        }

        List<List<Double>> result = embedDocuments(List.of(text));
        if (!CollectionUtils.isEmpty(result)) {
            return result.get(0);
        }
        return List.of();
    }
}