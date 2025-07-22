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

package org.salt.jlangchain.core.tts.aliyun;

import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.salt.jlangchain.ai.tts.aliyun.TtsAliyunClient;
import org.salt.jlangchain.ai.tts.aliyun.TtsAliyunRequest;
import org.salt.jlangchain.core.tts.TtsBase;
import org.salt.jlangchain.core.tts.card.TtsCard;
import org.salt.jlangchain.utils.JsonUtil;
import org.salt.jlangchain.utils.SpringContextUtil;

@Slf4j
@EqualsAndHashCode(callSuper = true)
@Data
public class AliyunTts extends TtsBase {

    protected String appkey = "Jzh7RR51jRXvaaDy";

    // Audio encoding format, supports PCM/WAV/MP3 format. Default value: pcm
    protected String format = "wav";

    // Pronunciation person, optional, default is xiaoyun
    protected String voice;

    // Volume, range 0~100, optional, default 50
    protected String volume = "50";

    // Speech rate, ranging from -500 to 500, optional, default is 0
    protected String speechRate = "0";

    // Intonation range is -500~500, optional, default is 0
    protected String pitchRate = "0";

    // Audio sampling rate, supports 16000 Hz and 8000 Hz, default value: 16000 Hz
    protected String sampleRate = "16000";

    @Override
    protected TtsCard callTts(String text) {
        log.debug("call doubao tts: {}", text);

        TtsAliyunClient ttsAliyunClient = SpringContextUtil.getBean(TtsAliyunClient.class);

        TtsAliyunRequest ttsRequest = new TtsAliyunRequest();

        ttsRequest.setAppkey(appkey);
        ttsRequest.setText(text);
        ttsRequest.setFormat(format);
        ttsRequest.setVoice(voice);
        ttsRequest.setVolume(String.valueOf(volume));
        ttsRequest.setSpeechRate(String.valueOf(speechRate));
        ttsRequest.setPitchRate(String.valueOf(pitchRate));
        ttsRequest.setSampleRate(sampleRate);

        log.info("doubao tts request: {}", JsonUtil.toJson(ttsRequest));
        try {
            String base64 = ttsAliyunClient.request(ttsRequest);
            log.info("doubao tts response");
            if (StringUtils.isEmpty(base64)) {
                log.error("doubao tts fail");
                return new TtsCard(text, null);
            }
            log.info("doubao tts response size: {}", base64.length());
            return new TtsCard(text, base64, true);
        } catch (Exception e) {
            log.error("doubao tts fail: {}", e.getMessage(), e);
            return new TtsCard(text, null);
        }
    }
}