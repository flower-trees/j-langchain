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

package org.salt.jlangchain.ai.vendor.aliyun.param;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

@Data
public class AliyunResponse {

    private Output output;
    private Usage usage;
    @JsonProperty("request_id")
    private String requestId;

    @Data
    public static class Output {
        @JsonProperty("finish_reason")
        private String finishReason;
        private String text;
    }

    @Data
    public static class Usage {
        @JsonProperty("total_tokens")
        private int totalTokens;
        @JsonProperty("input_tokens")
        private int inputTokens;
        @JsonProperty("output_tokens")
        private int outputTokens;
    }
}