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

import lombok.Data;

import java.util.List;

@Data
public class OllamaRequest {

    private String model;
    private List<Message> messages;
    private Options options;
    private boolean stream;
    private List<String> input;

    @Data
    public static class Message {
        private String role;
        private String content;
    }

    @Data
    public static class Options {
        private Double temperature;
    }
}
