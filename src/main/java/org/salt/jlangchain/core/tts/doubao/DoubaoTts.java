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

package org.salt.jlangchain.core.tts.doubao;

import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.extern.slf4j.Slf4j;
import org.salt.jlangchain.ai.tts.doubao.TtsDoubaoClient;
import org.salt.jlangchain.ai.tts.doubao.TtsDoubaoRequest;
import org.salt.jlangchain.ai.tts.doubao.TtsDoubaoResponse;
import org.salt.jlangchain.core.tts.TtsBase;
import org.salt.jlangchain.core.tts.card.TtsCard;
import org.salt.jlangchain.utils.JsonUtil;
import org.salt.jlangchain.utils.SpringContextUtil;

@Slf4j
@EqualsAndHashCode(callSuper = true)
@Data
public class DoubaoTts extends TtsBase {

    protected String appId = "6249868539";
    protected String userId = "123456";
    protected String cluster = "volcano_icl";
    protected String voiceType = "S_7zdqme4w1";
    protected String encoding = "wav";
    protected float speedRatio = 1.2f;
    protected float volumeRatio = 1.0f;
    protected float pitchRatio = 1.0f;
    protected String emotion = "happy";

    @Override
    protected TtsCard callTts(String text) {
        log.debug("call doubao tts: {}", text);

        TtsDoubaoClient ttsDoubaoClient = SpringContextUtil.getBean(TtsDoubaoClient.class);

        TtsDoubaoRequest ttsRequest = new TtsDoubaoRequest();

        ttsRequest.getApp().setAppid(appId);
        ttsRequest.getApp().setCluster(cluster);

        ttsRequest.getUser().setUid(userId);

        ttsRequest.getAudio().setVoiceType(voiceType);
        ttsRequest.getAudio().setEncoding(encoding);
        ttsRequest.getAudio().setSpeedRatio(speedRatio);
        ttsRequest.getAudio().setVolumeRatio(volumeRatio);
        ttsRequest.getAudio().setPitchRatio(pitchRatio);
        ttsRequest.getAudio().setEmotion(emotion);

        ttsRequest.getRequest().setText(text);

        log.info("doubao tts request: {}", JsonUtil.toJson(ttsRequest));
        TtsDoubaoResponse ttsDoubaoResponse  = ttsDoubaoClient.request(ttsRequest);
        log.info("doubao tts response: {}", ttsDoubaoResponse.getCode());

        TtsCard ttsCard = new TtsCard(1, text, ttsDoubaoResponse.getData());
        ttsCard.setAudio(true);
        return ttsCard;
    }
}