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

import lombok.extern.slf4j.Slf4j;
import org.salt.jlangchain.ai.tts.doubao.TtsDoubaoClient;
import org.salt.jlangchain.ai.tts.doubao.TtsDoubaoRequest;
import org.salt.jlangchain.ai.tts.doubao.TtsDoubaoResponse;
import org.salt.jlangchain.core.tts.TtsBase;
import org.salt.jlangchain.core.tts.card.TtsCard;
import org.salt.jlangchain.utils.SpringContextUtil;

@Slf4j
public class DoubaoTts extends TtsBase {

    @Override
    protected TtsCard callTts(String text) {
        log.debug("call doubao tts: {}", text);

        TtsDoubaoClient ttsDoubaoClient = SpringContextUtil.getBean(TtsDoubaoClient.class);

        TtsDoubaoRequest ttsRequest = new TtsDoubaoRequest();
        ttsRequest.getApp().setCluster("volcano_icl");
        ttsRequest.getUser().setUid("123456");
        ttsRequest.getAudio().setVoiceType("S_7zdqme4w1");
        ttsRequest.getRequest().setText(text);
        ttsRequest.getApp().setAppid("6249868539");

        TtsDoubaoResponse ttsDoubaoResponse  = ttsDoubaoClient.request(ttsRequest);

        log.debug("doubao tts response: {}", ttsDoubaoResponse.getCode());

        TtsCard ttsCard = new TtsCard(1, text, ttsDoubaoResponse.getData());
        ttsCard.setTts(true);
        return ttsCard;
    }
}