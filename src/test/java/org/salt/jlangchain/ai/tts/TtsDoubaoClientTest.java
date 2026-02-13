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

package org.salt.jlangchain.ai.tts;

import jakarta.annotation.Resource;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.salt.jlangchain.TestApplication;
import org.salt.jlangchain.ai.tts.doubao.TtsDoubaoClient;
import org.salt.jlangchain.ai.tts.doubao.TtsDoubaoRequest;
import org.salt.jlangchain.utils.JsonUtil;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.junit4.SpringRunner;

@RunWith(SpringRunner.class)
@SpringBootTest(classes = TestApplication.class)
@SpringBootConfiguration
public class TtsDoubaoClientTest {

    @Resource
    TtsDoubaoClient ttsDoubaoClient;

    @Test
    public void request() {

        TtsDoubaoRequest ttsRequest = new TtsDoubaoRequest();
        ttsRequest.getApp().setCluster("volcano_icl");
        ttsRequest.getUser().setUid("123456");
        ttsRequest.getAudio().setVoiceType("S_7zdqme4w1");
        ttsRequest.getRequest().setText("字节跳动人工智能实验室我要语音合成");
        ttsRequest.getApp().setAppid("6249868539");

        System.out.println(JsonUtil.toJson(ttsDoubaoClient.request(ttsRequest)));
    }
}