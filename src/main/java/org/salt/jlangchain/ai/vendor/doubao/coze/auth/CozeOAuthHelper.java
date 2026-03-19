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

package org.salt.jlangchain.ai.vendor.doubao.coze.auth;

import com.coze.openapi.client.auth.OAuthToken;
import com.coze.openapi.service.auth.JWTBuilder;
import com.coze.openapi.service.auth.JWTOAuthClient;
import com.coze.openapi.service.auth.JWTPayload;
import io.jsonwebtoken.Jwts;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.PrivateKey;
import java.util.Map;

@Slf4j
public class CozeOAuthHelper {

    private final CozeProperties properties;

    private JWTOAuthClient jwtOAuthClient;

    private OAuthToken accessToken;

    public CozeOAuthHelper(CozeProperties properties) {
        this.properties = properties;
    }

    @PostConstruct
    public void init() {
        if (properties != null
                && properties.getApiBase() != null
                && properties.getClientId() != null
                && properties.getPrivateKeyPath() != null
                && properties.getPublicKeyId() != null) {

            try {
                String privateKeyPem = Files.readString(Paths.get(properties.getPrivateKeyPath()), StandardCharsets.UTF_8);
                this.jwtOAuthClient = new JWTOAuthClient.JWTOAuthBuilder()
                        .jwtBuilder(new MyJWTBuilder())
                        .clientID(properties.getClientId())
                        .privateKey(privateKeyPem)
                        .publicKey(properties.getPublicKeyId())
                        .baseURL(properties.getApiBase())
                        .build();
                log.info("Coze OAuth success");
            } catch (Exception e) {
                log.error("Coze OAuth fail", e);
            }
        }
    }

    /**
     * 获取 access token
     */
    public synchronized OAuthToken getAccessToken() {
        if (jwtOAuthClient == null) {
            log.error("Coze OAuthClient is null");
            throw new RuntimeException("Coze OAuthClient is null");
        }
        if (accessToken != null && accessToken.getExpiresIn() - 10 > System.currentTimeMillis() / 1000) {
            return accessToken;
        }
        try {
            accessToken = jwtOAuthClient.getAccessToken();
            return accessToken;
        } catch (Exception e) {
            log.error("Failed to obtain access_token", e);
        }
        return null;
    }

    // 内部 JWT 构建器
    public static class MyJWTBuilder implements JWTBuilder {
        @Override
        public String generateJWT(PrivateKey privateKey, Map<String, Object> header, JWTPayload payload) {
            try {
                return Jwts.builder()
                        .header().add(header).and()
                        .issuer(payload.getIss())
                        .audience().add(payload.getAud()).and()
                        .issuedAt(payload.getIat())
                        .expiration(payload.getExp())
                        .claim("jti", payload.getJti())
                        .claim("session_name", payload.getSessionName())
                        .claim("session_context", payload.getSessionContext())
                        .signWith(privateKey, Jwts.SIG.RS256)
                        .compact();
            } catch (Exception e) {
                throw new RuntimeException("Failed to construct JWT", e);
            }
        }
    }
}
