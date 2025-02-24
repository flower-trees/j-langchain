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

package org.salt.jlangchain.rag.vector;

import io.milvus.v2.client.ConnectConfig;
import io.milvus.v2.client.MilvusClientV2;
import lombok.Getter;
import org.springframework.beans.factory.annotation.Value;

import javax.annotation.PostConstruct;

public class MilvusContainer {

    @Value("${rag.vector.milvus.host:localhost}")
    private String host;
    @Value("${rag.vector.milvus.port:19530}")
    private int port;
    @Value("${rag.vector.milvus.user:root}")
    private String user;
    @Value("${rag.vector.milvus.password:Milvus}")
    private String password;
    @Value("${rag.vector.milvus.secure:false}")
    private boolean secure;

    //todo: other ConnectConfig field


    @Getter
    MilvusClientV2 client;

    @PostConstruct
    private void init() {
        String clusterEndpoint = String.format("http://%s:%d", host, port);
        String token = String.format("%s:%s", user, password);
        ConnectConfig connectConfig = ConnectConfig.builder()
                .uri(clusterEndpoint)
                .token(token)
                .secure(secure)
                .build();
        client = new MilvusClientV2(connectConfig);
    }
}