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

package org.salt.jlangchain.config;

import lombok.extern.slf4j.Slf4j;
import org.salt.function.flow.FlowEngine;
import org.salt.jlangchain.ai.client.stream.HttpStreamClient;
import org.salt.jlangchain.ai.vendor.aliyun.AliyunActuator;
import org.salt.jlangchain.ai.vendor.chatgpt.ChatGPTActuator;
import org.salt.jlangchain.ai.vendor.doubao.DoubaoActuator;
import org.salt.jlangchain.ai.vendor.moonshot.MoonshotActuator;
import org.salt.jlangchain.ai.vendor.ollama.OllamaActuator;
import org.salt.jlangchain.core.ChainActor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Slf4j
@Configuration
public class JLangchainConfig {

    @Bean
    public HttpStreamClient chatGPTHttpClient() {
        HttpStreamClient client = new HttpStreamClient();
        client.setProxyHost("127.0.0.1");
        client.setProxyPort(1087);
        return client;
    }

    @Bean
    public HttpStreamClient commonHttpClient() {
        return new HttpStreamClient();
    }

    @Bean
    public ChainActor chain(@Autowired(required = false) FlowEngine flowEngine) {
        if (flowEngine == null) {
            throw new RuntimeException("flowEngine is null");
        }
        return new ChainActor(flowEngine);
    }

    @Bean
    public ChatGPTActuator chatGPTActuator(HttpStreamClient chatGPTHttpClient) {
        return new ChatGPTActuator(chatGPTHttpClient);
    }

    @Bean
    public OllamaActuator ollamaActuator(HttpStreamClient commonHttpClient) {
        return new OllamaActuator(commonHttpClient);
    }

    @Bean
    public DoubaoActuator doubaoActuator(HttpStreamClient commonHttpClient) {
        return new DoubaoActuator(commonHttpClient);
    }

    @Bean
    public AliyunActuator aliyunActuator(HttpStreamClient commonHttpClient) {
        return new AliyunActuator(commonHttpClient);
    }

    @Bean
    public MoonshotActuator moonshotActuator(HttpStreamClient commonHttpClient) {
        return new MoonshotActuator(commonHttpClient);
    }
}
