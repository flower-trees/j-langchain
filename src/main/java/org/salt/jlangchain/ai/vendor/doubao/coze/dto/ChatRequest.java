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

package org.salt.jlangchain.ai.vendor.doubao.coze.dto;


import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ChatRequest {

    @JsonProperty("bot_id")
    private String botId;

    @JsonProperty("user_id")
    private String userId;

    /** Whether to use SSE streaming return */
    private boolean stream = true;

    @JsonProperty("additional_messages")
    private List<AdditionalMessage> additionalMessages;

    /** Reserved extension parameters, can be passed with an empty Map */
    private Map<String, Object> parameters;
}