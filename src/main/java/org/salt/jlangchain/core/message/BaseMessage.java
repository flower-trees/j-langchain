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

package org.salt.jlangchain.core.message;

import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;
import org.salt.jlangchain.core.Serializable;

import java.util.Map;

@EqualsAndHashCode(callSuper = true)
@Data
@SuperBuilder
@NoArgsConstructor
public class BaseMessage extends Serializable {
    protected String id;
    protected String role;
    protected String content;
    protected Map<String, Object> responseMetadata;
    protected Map<String, Object> additionalKwargs;
    protected String finishReason;

    public static BaseMessage fromMessage(String role, String message) {
        return switch (MessageType.fromCode(role)) {
            case SYSTEM -> SystemMessage.builder().content(message).build();
            case AI -> AIMessage.builder().content(message).build();
            case HUMAN -> HumanMessage.builder().content(message).build();
        };
    }
}
