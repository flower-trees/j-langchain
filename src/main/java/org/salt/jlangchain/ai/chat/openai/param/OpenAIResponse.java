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

package org.salt.jlangchain.ai.chat.openai.param;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.List;

@Data
public class OpenAIResponse {

    private String id;
    private String object;
    private int created;
    private String model;
    @JsonProperty("system_fingerprint")
    private String systemFingerprint;
    private List<Choice> choices;
    private List<DataObject> data;

    @Data
    public static class Choice {
        private int index;
        private Delta delta;
        private Object logprobs;
        @JsonProperty("finish_reason")
        private String finishReason;
        private Delta message;

        @Data
        public static class Delta {
            private String role;
            private String content;
        }
    }

    @Data
    public static class DataObject {
        private String object;
        private int index;
        private List<Float> embedding;
    }
}

