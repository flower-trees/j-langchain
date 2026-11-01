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

package org.salt.jlangchain.core.llm.custom;

import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.extern.slf4j.Slf4j;
import org.salt.jlangchain.ai.common.param.AiChatInput;
import org.salt.jlangchain.ai.chat.strategy.AiChatActuator;
import org.salt.jlangchain.ai.vendor.custom.CustomActuator;
import org.salt.jlangchain.core.llm.BaseChatModel;

import java.util.List;
import java.util.Map;

/**
 * Chat model for any OpenAI-Chat-Completions-compatible endpoint that isn't one of the named
 * vendors — OpenRouter, Together, Groq, a self-hosted server, a corporate gateway. See
 * {@link CustomActuator} for how the URL/key are configured; there is no built-in default
 * endpoint, unlike every other vendor here.
 * <p>
 * {@code model} is passed through to the request body verbatim, so vendor-qualified model ids
 * (e.g. {@code "nvidia/nemotron-3-ultra-550b-a55b:free"} on OpenRouter) work unchanged.
 */
@Slf4j
@EqualsAndHashCode(callSuper = true)
@Data
public class ChatCustom extends BaseChatModel {

    public ChatCustom() {
    }

    protected ChatCustom(ChatCustomBuilder<?, ?> builder) {
        super();
        this.vendor = builder.vendorSet ? builder.vendor : this.vendor;
        this.modelType = builder.modelTypeSet ? builder.modelType : this.modelType;
        this.model = builder.modelSet ? builder.model : this.model;
        this.temperature = builder.temperatureSet ? builder.temperature : this.temperature;
        this.modelKwargs = builder.modelKwargs;
        this.tools = builder.tools;
    }

    public static ChatCustomBuilder<?, ?> builder() {
        return new ChatCustomBuilderImpl();
    }

    public static abstract class ChatCustomBuilder<C extends ChatCustom, B extends ChatCustomBuilder<C, B>> {
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
            return "ChatCustom.ChatCustomBuilder(vendor=" + this.vendor + ", modelType=" + this.modelType + ", model=" + this.model + ", temperature=" + this.temperature + ", modelKwargs=" + this.modelKwargs + ", tools=" + this.tools + ")";
        }
    }

    private static final class ChatCustomBuilderImpl extends ChatCustomBuilder<ChatCustom, ChatCustomBuilderImpl> {
        private ChatCustomBuilderImpl() {
        }

        @Override
        protected ChatCustomBuilderImpl self() {
            return this;
        }

        @Override
        public ChatCustom build() {
            return new ChatCustom(this);
        }
    }

    private boolean jsonMode = false;

    @Override
    public BaseChatModel copy() {
        return ChatCustom.builder()
                .vendor(this.vendor).modelType(this.modelType).model(this.model)
                .temperature(this.temperature).modelKwargs(this.modelKwargs).build();
    }

    @Override
    public BaseChatModel withJsonMode() {
        ChatCustom copy = (ChatCustom) copy();
        copy.jsonMode = true;
        return copy;
    }

    @Override
    public void otherInformation(AiChatInput aiChatInput) {
        aiChatInput.setModel(model);
        aiChatInput.setTemperature(temperature);
        aiChatInput.setTools(tools);
        if (jsonMode) {
            AiChatInput.ResponseFormat responseFormat = new AiChatInput.ResponseFormat();
            responseFormat.setType("json_object");
            aiChatInput.setResponseFormat(responseFormat);
        }
    }

    @Override
    public Class<? extends AiChatActuator> getActuator() {
        return CustomActuator.class;
    }
}
