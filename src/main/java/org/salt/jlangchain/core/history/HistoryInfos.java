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

package org.salt.jlangchain.core.history;

import lombok.Data;
import org.salt.jlangchain.core.message.BaseMessage;

import java.util.List;
import java.util.UUID;

@Data
public class HistoryInfos {

    public enum Type {
        /** Normal Human+AI conversation turn. */
        NORMAL,
        /** Rolling summary of earlier turns (injected into system prompt). */
        SUMMARY,
        /** One agent tool-call + result step recorded in AgentTaskStorage. */
        AGENT_STEP,
        /** Compressed summary of older agent steps (replaces AGENT_STEP entries). */
        TASK_SUMMARY
    }

    /** Unique record id — auto-generated as UUID if not set. */
    private String id;
    /** Parent record id: null for top-level ConversationStorage entries; points to parent for AgentTaskStorage entries. */
    private String parentId;
    /** Creation timestamp (epoch ms). */
    private long createdAt;

    private Type type = Type.NORMAL;
    private List<BaseMessage> messages;

    protected HistoryInfos(HistoryInfosBuilder builder) {
        this.id        = builder.id != null ? builder.id : UUID.randomUUID().toString();
        this.parentId  = builder.parentId;
        this.createdAt = builder.createdAt > 0 ? builder.createdAt : System.currentTimeMillis();
        this.type      = builder.type != null ? builder.type : Type.NORMAL;
        this.messages  = builder.messages;
    }

    public HistoryInfos() {
        this.id        = UUID.randomUUID().toString();
        this.createdAt = System.currentTimeMillis();
    }

    public static HistoryInfosBuilder builder() {
        return new HistoryInfosBuilder();
    }

    public static final class HistoryInfosBuilder {
        private String id;
        private String parentId;
        private long createdAt;
        private Type type;
        private List<BaseMessage> messages;

        private HistoryInfosBuilder() {
        }

        public HistoryInfosBuilder id(String id) {
            this.id = id;
            return this;
        }

        public HistoryInfosBuilder parentId(String parentId) {
            this.parentId = parentId;
            return this;
        }

        public HistoryInfosBuilder createdAt(long createdAt) {
            this.createdAt = createdAt;
            return this;
        }

        public HistoryInfosBuilder type(Type type) {
            this.type = type;
            return this;
        }

        public HistoryInfosBuilder messages(List<BaseMessage> messages) {
            this.messages = messages;
            return this;
        }

        public HistoryInfos build() {
            return new HistoryInfos(this);
        }
    }
}
