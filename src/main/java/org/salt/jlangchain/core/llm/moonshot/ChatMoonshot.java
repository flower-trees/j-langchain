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

package org.salt.jlangchain.core.llm.moonshot;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.salt.jlangchain.ai.chat.strategy.AiChatActuator;
import org.salt.jlangchain.ai.common.param.AiChatInput;
import org.salt.jlangchain.ai.vendor.moonshot.MoonshotActuator;
import org.salt.jlangchain.core.llm.BaseChatModel;

import java.util.List;
import java.util.Map;

@Slf4j
@Data
public class ChatMoonshot extends BaseChatModel {

    protected String vendor = "moonshot";
    protected String modelType = "llm";
    protected String model = "moonshot-v1-8k";
    protected Float temperature = 0.7f;
    protected Map<String, Object> modelKwargs;
    protected List<AiChatInput.Tool> tools;

    public ChatMoonshot() {
    }

    protected ChatMoonshot(ChatMoonshotBuilder<?, ?> builder) {
        super();
        this.vendor = builder.vendorSet ? builder.vendor : this.vendor;
        this.modelType = builder.modelTypeSet ? builder.modelType : this.modelType;
        this.model = builder.modelSet ? builder.model : this.model;
        this.temperature = builder.temperatureSet ? builder.temperature : this.temperature;
        this.modelKwargs = builder.modelKwargs;
        this.tools = builder.tools;
    }

    public static ChatMoonshotBuilder<?, ?> builder() {
        return new ChatMoonshotBuilderImpl();
    }

    public static abstract class ChatMoonshotBuilder<C extends ChatMoonshot, B extends ChatMoonshotBuilder<C, B>> {
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
            return "ChatMoonshot.ChatMoonshotBuilder(vendor=" + this.vendor + ", modelType=" + this.modelType + ", model=" + this.model + ", temperature=" + this.temperature + ", modelKwargs=" + this.modelKwargs + ", tools=" + this.tools + ")";
        }
    }

    private static final class ChatMoonshotBuilderImpl extends ChatMoonshotBuilder<ChatMoonshot, ChatMoonshotBuilderImpl> {
        private ChatMoonshotBuilderImpl() {
        }

        @Override
        protected ChatMoonshotBuilderImpl self() {
            return this;
        }

        @Override
        public ChatMoonshot build() {
            return new ChatMoonshot(this);
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
        return MoonshotActuator.class;
    }
}
