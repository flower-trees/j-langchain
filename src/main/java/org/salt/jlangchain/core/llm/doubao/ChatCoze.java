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

package org.salt.jlangchain.core.llm.doubao;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.salt.jlangchain.ai.chat.strategy.AiChatActuator;
import org.salt.jlangchain.ai.common.param.AiChatInput;
import org.salt.jlangchain.ai.vendor.doubao.coze.CozeActuator;
import org.salt.jlangchain.core.llm.BaseChatModel;

import java.util.Map;

@Slf4j
@Data
public class ChatCoze extends BaseChatModel {

    protected String vendor = "doubao";
    protected String botId = "751971414224112XXXX";
    protected Map<String, Object> modelKwargs;
    protected String userId = "123";
    protected String key;

    public ChatCoze() {
    }

    protected ChatCoze(ChatCozeBuilder<?, ?> builder) {
        super();
        this.vendor = builder.vendorSet ? builder.vendor : this.vendor;
        this.botId = builder.botIdSet ? builder.botId : this.botId;
        this.modelKwargs = builder.modelKwargs;
        this.userId = builder.userIdSet ? builder.userId : this.userId;
        this.key = builder.key;
    }

    public static ChatCozeBuilder<?, ?> builder() {
        return new ChatCozeBuilderImpl();
    }

    public static abstract class ChatCozeBuilder<C extends ChatCoze, B extends ChatCozeBuilder<C, B>> {
        private String vendor;
        private boolean vendorSet;
        private String botId;
        private boolean botIdSet;
        private Map<String, Object> modelKwargs;
        private String userId;
        private boolean userIdSet;
        private String key;

        protected abstract B self();

        public abstract C build();

        public B vendor(String vendor) {
            this.vendor = vendor;
            this.vendorSet = true;
            return self();
        }

        public B botId(String botId) {
            this.botId = botId;
            this.botIdSet = true;
            return self();
        }

        public B modelKwargs(Map<String, Object> modelKwargs) {
            this.modelKwargs = modelKwargs;
            return self();
        }

        public B userId(String userId) {
            this.userId = userId;
            this.userIdSet = true;
            return self();
        }

        public B key(String key) {
            this.key = key;
            return self();
        }

        @Override
        public String toString() {
            return "ChatCoze.ChatCozeBuilder(vendor=" + this.vendor + ", botId=" + this.botId + ", modelKwargs=" + this.modelKwargs + ", userId=" + this.userId + ", key=" + this.key + ")";
        }
    }

    private static final class ChatCozeBuilderImpl extends ChatCozeBuilder<ChatCoze, ChatCozeBuilderImpl> {
        private ChatCozeBuilderImpl() {
        }

        @Override
        protected ChatCozeBuilderImpl self() {
            return this;
        }

        @Override
        public ChatCoze build() {
            return new ChatCoze(this);
        }
    }

    @Override
    public void otherInformation(AiChatInput aiChatInput) {
        aiChatInput.setBotId(botId);
        aiChatInput.setUserId(userId);
        aiChatInput.setKey(key);
    }

    public Class<? extends AiChatActuator> getActuator() {
        return CozeActuator.class;
    }
}
