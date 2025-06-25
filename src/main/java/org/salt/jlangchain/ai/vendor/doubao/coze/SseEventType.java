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

package org.salt.jlangchain.ai.vendor.doubao.coze;

import lombok.Getter;

@Getter
public enum SseEventType {
    CHAT_CREATED("conversation.chat.created"),
    CHAT_IN_PROGRESS("conversation.chat.in_progress"),
    MESSAGE_DELTA("conversation.message.delta"),
    MESSAGE_COMPLETED("conversation.message.completed"),
    CHAT_COMPLETED("conversation.chat.completed"),
    DONE("done")
    ;

    private final String code;

    SseEventType(String code) {
        this.code = code;
    }
}
