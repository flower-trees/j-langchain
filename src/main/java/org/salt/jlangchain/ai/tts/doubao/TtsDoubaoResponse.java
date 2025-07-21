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

package org.salt.jlangchain.ai.tts.doubao;

import lombok.Data;

@Data
public class TtsDoubaoResponse {

    private String reqid;
    private int code;
    private String operation;
    private String message;
    private int sequence;
    private String data;
    private Addition addition;

    @Data
    public static class Addition {
        private String duration;
        private String first_pkg;
    }
}