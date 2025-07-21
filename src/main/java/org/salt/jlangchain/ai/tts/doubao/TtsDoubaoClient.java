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

import org.salt.jlangchain.ai.client.stream.HttpStreamClient;
import org.salt.jlangchain.utils.JsonUtil;
import org.springframework.beans.factory.annotation.Value;

import java.util.Map;

public class TtsDoubaoClient {

    HttpStreamClient commonHttpClient;

    @Value("${tts.doubao.api-url:https://openspeech.bytedance.com/api/v1/tts}")
    protected String API_URL;

    @Value("${tts.doubao.api-key:${DOUBAO_TTS_KEY:}}")
    protected String ACCESS_TOKEN;

    public TtsDoubaoClient(HttpStreamClient commonHttpClient) {
        this.commonHttpClient = commonHttpClient;
    }

    public TtsDoubaoResponse request(TtsDoubaoRequest ttsDoubaoRequest) {
        ttsDoubaoRequest.getApp().setToken(ACCESS_TOKEN);
        return commonHttpClient.request(API_URL, JsonUtil.toJson(ttsDoubaoRequest), Map.of("Authorization", "Bearer; " + ACCESS_TOKEN), TtsDoubaoResponse.class);
    }
}
