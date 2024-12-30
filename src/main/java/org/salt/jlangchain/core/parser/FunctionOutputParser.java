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

import org.apache.commons.lang3.StringUtils;
import org.salt.jlangchain.core.parser.generation.ChatGeneration;
import org.salt.jlangchain.core.parser.generation.Generation;

import java.util.function.Function;

public class FunctionOutputParser extends BaseTransformOutputParser {

    Function<String, String> func;

    public FunctionOutputParser(Function<String, String> func) {
        super();
        this.func = func;
    }

    @Override
    protected Generation parse(Generation input) {
        if (input instanceof ChatGeneration chatGeneration) {
            if (StringUtils.isNotBlank(chatGeneration.getText())) {
                chatGeneration.setText(func.apply(chatGeneration.getText()));
                if (chatGeneration.getMessage() != null) {
                    if (StringUtils.isNotBlank(chatGeneration.getMessage().getContent())) {
                        chatGeneration.getMessage().setContent(chatGeneration.getText());
                    }
                }
            }
            return input;
        } else {
            throw new RuntimeException("Unsupported input type: " + input.getClass().getName());
        }
    }
}
