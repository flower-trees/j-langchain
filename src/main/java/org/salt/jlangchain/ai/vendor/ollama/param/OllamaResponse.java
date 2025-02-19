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

package org.salt.jlangchain.ai.vendor.ollama.param;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.List;

@Data
public class OllamaResponse {
    private String model;
    private String created_at;
    private Message message;
    @JsonProperty("done_reason")
    private String doneReason;
    private boolean done;
    @JsonProperty("total_duration")
    private long totalDuration;
    @JsonProperty("load_duration")
    private long loadDuration;
    @JsonProperty("prompt_eval_count")
    private int promptEvalCount;
    @JsonProperty("prompt_eval_duration")
    private long promptEvalDuration;
    @JsonProperty("eval_count")
    private int evalCount;
    @JsonProperty("eval_duration")
    private long evalDuration;
    private List<DataObject> data;

    @Data
    public static class Message {
        private String role;
        private String content;
    }

    @Data
    public static class DataObject {
        private String object;
        private int index;
        private List<Double> embedding;
    }
}

