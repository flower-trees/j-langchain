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

import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.salt.jlangchain.utils.JsonUtil;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import javax.xml.bind.DatatypeConverter;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.text.SimpleDateFormat;
import java.util.*;

@Slf4j
public class AliyunTokenUtil {

    private static final String TIME_ZONE = "GMT";
    private static final String FORMAT_ISO8601 = "yyyy-MM-dd'T'HH:mm:ss'Z'";
    private static final String URL_ENCODING = "UTF-8";
    private static final String ALGORITHM_NAME = "HmacSHA1";
    private static final String ENCODING = "UTF-8";

    private static final String REGION_ID = "cn-shanghai";
    private static final String ENDPOINT = "http://nls-meta.cn-shanghai.aliyuncs.com";

    private final String accessKeyId;
    private final String accessKeySecret;

    private static String cachedToken = null;
    private static long cachedExpireTime = 0;

    public AliyunTokenUtil(String accessKeyId, String accessKeySecret) {
        this.accessKeyId = accessKeyId;
        this.accessKeySecret = accessKeySecret;
    }

    public String getToken() {
        long now = System.currentTimeMillis() / 1000;
        if (cachedToken != null && now < cachedExpireTime - 60) {
            return cachedToken;
        }
        return fetchToken();
    }

    public long getCachedExpireTime() {
        return cachedExpireTime;
    }

    private String fetchToken() {
        Map<String, String> queryParamsMap = new HashMap<>();
        queryParamsMap.put("AccessKeyId", accessKeyId);
        queryParamsMap.put("Action", "CreateToken");
        queryParamsMap.put("Version", "2019-02-28");
        queryParamsMap.put("Timestamp", getISO8601Time(null));
        queryParamsMap.put("Format", "JSON");
        queryParamsMap.put("RegionId", REGION_ID);
        queryParamsMap.put("SignatureMethod", "HMAC-SHA1");
        queryParamsMap.put("SignatureVersion", "1.0");
        queryParamsMap.put("SignatureNonce", UUID.randomUUID().toString());

        String queryString = canonicalizedQuery(queryParamsMap);
        String stringToSign = createStringToSign(queryString);
        String signature = sign(stringToSign, accessKeySecret + "&");
        String fullQuery = "Signature=" + signature + "&" + queryString;

        String url = ENDPOINT + "/?" + fullQuery;

        try {
            OkHttpClient client = new OkHttpClient();
            Request request = new Request.Builder()
                    .url(url)
                    .header("Accept", "application/json")
                    .get()
                    .build();

            try (Response response = client.newCall(request).execute()) {
                if (response.isSuccessful() && response.body() != null) {
                    String result = response.body().string();
                    JsonNode root = JsonUtil.fromJson(result);
                    if (root != null) {
                        JsonNode tokenObj = root.get("Token");
                        if (tokenObj != null) {
                            cachedToken = tokenObj.get("Id").asText();
                            cachedExpireTime = tokenObj.get("ExpireTime").asLong();
                            return cachedToken;
                        }
                    }
                } else {
                    log.error("http request call fail, e:{}", response);
                }
            } catch (Exception e) {
                log.error("http request call fail, e:{}", e.getMessage(), e);
            }
        } catch (Exception e) {
            log.error("http request call fail, e:{}", e.getMessage(), e);
        }
        return null;
    }

    private static String getISO8601Time(Date date) {
        SimpleDateFormat df = new SimpleDateFormat(FORMAT_ISO8601);
        df.setTimeZone(new SimpleTimeZone(0, TIME_ZONE));
        return df.format(date != null ? date : new Date());
    }

    private static String percentEncode(String value) throws UnsupportedEncodingException {
        return value != null ? URLEncoder.encode(value, URL_ENCODING)
                .replace("+", "%20")
                .replace("*", "%2A")
                .replace("%7E", "~") : null;
    }

    private static String canonicalizedQuery(Map<String, String> queryParamsMap) {
        String[] sortedKeys = queryParamsMap.keySet().toArray(new String[0]);
        Arrays.sort(sortedKeys);
        StringBuilder sb = new StringBuilder();
        try {
            for (String key : sortedKeys) {
                sb.append("&").append(percentEncode(key))
                        .append("=").append(percentEncode(queryParamsMap.get(key)));
            }
        } catch (UnsupportedEncodingException e) {
            log.error("Encoding error in canonicalizedQuery", e);
        }
        return sb.substring(1); // remove first '&'
    }

    private static String createStringToSign(String queryString) {
        try {
            return "GET" + "&" + percentEncode("/") + "&" + percentEncode(queryString);
        } catch (UnsupportedEncodingException e) {
            throw new RuntimeException("Encoding error in createStringToSign", e);
        }
    }

    private static String sign(String stringToSign, String accessKeySecret) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM_NAME);
            mac.init(new SecretKeySpec(accessKeySecret.getBytes(ENCODING), ALGORITHM_NAME));
            byte[] signData = mac.doFinal(stringToSign.getBytes(ENCODING));
            String signBase64 = DatatypeConverter.printBase64Binary(signData);
            return percentEncode(signBase64);
        } catch (Exception e) {
            throw new RuntimeException("Error while signing string", e);
        }
    }
}