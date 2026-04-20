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

@Data
public class HistoryInfos {

    public enum Type { NORMAL, SUMMARY }

    private Type type = Type.NORMAL;
    private List<BaseMessage> messages;

    protected HistoryInfos(HistoryInfosBuilder builder) {
        this.type = builder.type != null ? builder.type : Type.NORMAL;
        this.messages = builder.messages;
    }

    public HistoryInfos() {
    }

    public static HistoryInfosBuilder builder() {
        return new HistoryInfosBuilder();
    }

    public static final class HistoryInfosBuilder {
        private Type type;
        private List<BaseMessage> messages;

        private HistoryInfosBuilder() {
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
