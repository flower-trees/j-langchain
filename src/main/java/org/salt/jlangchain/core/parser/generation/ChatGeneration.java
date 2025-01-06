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

package org.salt.jlangchain.core.parser.generation;

import lombok.Getter;
import lombok.Setter;
import org.salt.jlangchain.core.message.BaseMessage;

@Setter
@Getter
public class ChatGeneration extends Generation {

    protected BaseMessage message;

    public ChatGeneration(BaseMessage message) {
        super("");
        if (message != null) {
            super.setText(message.getContent());
            this.message = BaseMessage.fromMessage(message.getRole(), message.getContent());
        }
    }
}
