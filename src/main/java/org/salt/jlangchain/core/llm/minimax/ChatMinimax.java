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

package org.salt.jlangchain.core.llm.minimax;

import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.extern.slf4j.Slf4j;
import org.salt.jlangchain.ai.chat.strategy.AiChatActuator;
import org.salt.jlangchain.ai.common.param.AiChatInput;
import org.salt.jlangchain.ai.vendor.minimax.MinimaxActuator;
import org.salt.jlangchain.core.llm.BaseChatModel;

import java.util.List;
import java.util.Map;

@Slf4j
@EqualsAndHashCode(callSuper = true)
@Data
public class ChatMinimax extends BaseChatModel {

    protected String vendor = "minimax";
    protected String modelType = "llm";
    protected String model = "MiniMax-Text-01";
    protected Float temperature = 0.7f;
    protected Map<String, Object> modelKwargs;
    protected List<AiChatInput.Tool> tools;

    public ChatMinimax() {
    }

    protected ChatMinimax(ChatMinimaxBuilder<?, ?> builder) {
        super();
        this.vendor = builder.vendorSet ? builder.vendor : this.vendor;
        this.modelType = builder.modelTypeSet ? builder.modelType : this.modelType;
        this.model = builder.modelSet ? builder.model : this.model;
        this.temperature = builder.temperatureSet ? builder.temperature : this.temperature;
        this.modelKwargs = builder.modelKwargs;
        this.tools = builder.tools;
    }

    public static ChatMinimaxBuilder<?, ?> builder() {
        return new ChatMinimaxBuilderImpl();
    }

    public static abstract class ChatMinimaxBuilder<C extends ChatMinimax, B extends ChatMinimaxBuilder<C, B>> {
        private String vendor;
        private boolean vendorSet;
        private String modelType;
        private boolean modelTypeSet;
        private String model;
        private boolean modelSet;
        private Float temperature;
        private boolean temperatureSet;
        private Map<String, Object> modelKwargs;
        private List<AiChatInput.Tool> tools;

        protected abstract B self();

        public abstract C build();

        public B vendor(String vendor) {
            this.vendor = vendor;
            this.vendorSet = true;
            return self();
        }

        public B modelType(String modelType) {
            this.modelType = modelType;
            this.modelTypeSet = true;
            return self();
        }

        public B model(String model) {
            this.model = model;
            this.modelSet = true;
            return self();
        }

        public B temperature(Float temperature) {
            this.temperature = temperature;
            this.temperatureSet = true;
            return self();
        }

        public B modelKwargs(Map<String, Object> modelKwargs) {
            this.modelKwargs = modelKwargs;
            return self();
        }

        public B tools(List<AiChatInput.Tool> tools) {
            this.tools = tools;
            return self();
        }

        @Override
        public String toString() {
            return "ChatMinimax.ChatMinimaxBuilder(vendor=" + this.vendor + ", modelType=" + this.modelType + ", model=" + this.model + ", temperature=" + this.temperature + ", modelKwargs=" + this.modelKwargs + ", tools=" + this.tools + ")";
        }
    }

    private static final class ChatMinimaxBuilderImpl extends ChatMinimaxBuilder<ChatMinimax, ChatMinimaxBuilderImpl> {
        private ChatMinimaxBuilderImpl() {
        }

        @Override
        protected ChatMinimaxBuilderImpl self() {
            return this;
        }

        @Override
        public ChatMinimax build() {
            return new ChatMinimax(this);
        }
    }

    @Override
    public void otherInformation(AiChatInput aiChatInput) {
        aiChatInput.setModel(model);
        aiChatInput.setTemperature(temperature);
        aiChatInput.setTools(tools);
    }

    @Override
    public Class<? extends AiChatActuator> getActuator() {
        return MinimaxActuator.class;
    }
}
