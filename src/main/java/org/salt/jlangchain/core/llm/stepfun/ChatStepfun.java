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

package org.salt.jlangchain.core.llm.stepfun;

import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.extern.slf4j.Slf4j;
import org.salt.jlangchain.ai.chat.strategy.AiChatActuator;
import org.salt.jlangchain.ai.common.param.AiChatInput;
import org.salt.jlangchain.ai.vendor.stepfun.StepfunActuator;
import org.salt.jlangchain.core.llm.BaseChatModel;

import java.util.List;
import java.util.Map;

@Slf4j
@EqualsAndHashCode(callSuper = true)
@Data
public class ChatStepfun extends BaseChatModel {

    public ChatStepfun() {
        this.vendor = "stepfun";
        this.model = "step-2-16k";
    }

    protected ChatStepfun(ChatStepfunBuilder<?, ?> builder) {
        super();
        this.vendor = "stepfun";
        this.model = "step-2-16k";
        this.vendor = builder.vendorSet ? builder.vendor : this.vendor;
        this.modelType = builder.modelTypeSet ? builder.modelType : this.modelType;
        this.model = builder.modelSet ? builder.model : this.model;
        this.temperature = builder.temperatureSet ? builder.temperature : this.temperature;
        this.modelKwargs = builder.modelKwargs;
        this.tools = builder.tools;
    }

    public static ChatStepfunBuilder<?, ?> builder() {
        return new ChatStepfunBuilderImpl();
    }

    public static abstract class ChatStepfunBuilder<C extends ChatStepfun, B extends ChatStepfunBuilder<C, B>> {
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
            return "ChatStepfun.ChatStepfunBuilder(vendor=" + this.vendor + ", modelType=" + this.modelType + ", model=" + this.model + ", temperature=" + this.temperature + ", modelKwargs=" + this.modelKwargs + ", tools=" + this.tools + ")";
        }
    }

    private static final class ChatStepfunBuilderImpl extends ChatStepfunBuilder<ChatStepfun, ChatStepfunBuilderImpl> {
        private ChatStepfunBuilderImpl() {
        }

        @Override
        protected ChatStepfunBuilderImpl self() {
            return this;
        }

        @Override
        public ChatStepfun build() {
            return new ChatStepfun(this);
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
        return StepfunActuator.class;
    }
}
