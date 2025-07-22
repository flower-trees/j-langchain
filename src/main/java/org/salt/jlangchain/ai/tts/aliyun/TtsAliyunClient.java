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

package org.salt.jlangchain.ai.tts.aliyun;

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.salt.jlangchain.ai.client.stream.HttpStreamClient;
import org.salt.jlangchain.utils.JsonUtil;
import org.springframework.beans.factory.annotation.Value;

import java.io.IOException;
import java.util.Base64;
import java.util.Map;

@Slf4j
public class TtsAliyunClient {

    HttpStreamClient commonHttpClient;

    @Value("${tts.aliyun.api-url:https://nls-gateway-cn-shanghai.aliyuncs.com/stream/v1/tts}")
    protected String API_URL;

    @Value("${tts.aliyun.api-key:${ALIYUN_TTS_KEY:}}")
    protected String ACCESS_TOKEN;

    @Value("${tts.aliyun.api-ak-id:${ALIYUN_AK_ID:}}")
    protected String ALIYUN_AK_ID;
    @Value("${tts.aliyun.api-ak-secret:${ALIYUN_AK_SECRET:}}")
    protected String ALIYUN_AK_SECRET;

    public TtsAliyunClient(HttpStreamClient commonHttpClient) {
        this.commonHttpClient = commonHttpClient;
    }

    public String request(TtsAliyunRequest ttsAliyunRequest) {
        // get access token
        if (StringUtils.isEmpty(ACCESS_TOKEN)) {
            AliyunTokenUtil tokenUtil = new AliyunTokenUtil(ALIYUN_AK_ID, ALIYUN_AK_SECRET);
            ttsAliyunRequest.setToken(tokenUtil.getToken());
        } else {
            ttsAliyunRequest.setToken(ACCESS_TOKEN);
        }

        return commonHttpClient.request(API_URL, JsonUtil.toJson(ttsAliyunRequest), Map.of(), response -> {
            String contentType = response.header("Content-Type");
            if ("audio/mpeg".equals(contentType)) {
                log.debug("http request success");
                if (response.body() != null) {
                    try {
                        return Base64.getEncoder().encodeToString(response.body().bytes());
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                }
            } else {
                try {
                    log.error("http request call fail, e:response code: {}, msg:{}", response.code(), response.body() != null ? new String(response.body().bytes()) : "");
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            }
            return null;
        });
    }
}
