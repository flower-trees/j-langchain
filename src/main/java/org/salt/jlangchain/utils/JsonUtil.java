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

package org.salt.jlangchain.utils;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
public class JsonUtil {

    @Getter
    public static final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * Same wire format as {@link #objectMapper} (snake_case field names, tolerant of unknown
     * properties, omits nulls) but WITHOUT the Long-&gt;String serializer below.
     * <p>
     * Use this (via {@link #toJsonStrict(Object)}) for any payload that must stay a faithful,
     * spec-compliant JSON encoding of its Java types — most notably outgoing LLM API request
     * bodies. The Long-&gt;String rule exists to protect large snowflake-style IDs from
     * JavaScript's Number precision loss when *our own* responses reach a browser; it has
     * nothing to do with third-party API payloads. Passed through unchanged, it silently
     * corrupts them: a passthrough MCP tool JSON Schema can carry a numeric bound that happens
     * to exceed Integer range (e.g. zod-to-json-schema emits `maximum: Number.MAX_SAFE_INTEGER`
     * = 9007199254740991 for `z.number().int()`), which Jackson deserializes as a Java Long:
     * fine on its own, but {@link #objectMapper} then re-serializes that Long as the JSON string
     * "9007199254740991" instead of a bare number, and the vendor rejects the whole tool-call
     * request with "... is not of type number" — observed for real against DeepSeek with the
     * Playwright MCP server's browser_network_request tool.
     */
    @Getter
    public static final ObjectMapper strictObjectMapper = new ObjectMapper();

    static {
        SimpleModule module = new SimpleModule();
        module.addSerializer(Long.class, new ToStringSerializer());
        objectMapper.registerModule(module);
        objectMapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        objectMapper.configure(SerializationFeature.FAIL_ON_EMPTY_BEANS, false);
        objectMapper.setSerializationInclusion(JsonInclude.Include.NON_NULL);
        objectMapper.setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);

        strictObjectMapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        strictObjectMapper.configure(SerializationFeature.FAIL_ON_EMPTY_BEANS, false);
        strictObjectMapper.setSerializationInclusion(JsonInclude.Include.NON_NULL);
        strictObjectMapper.setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
    }

    public static boolean isValidJson(String json) {
        try {
            objectMapper.readTree(json);
            return true;
        } catch (JsonProcessingException e) {
            return false;
        }
    }

    public static String toJson(Object o) {
        try {
            return objectMapper.writeValueAsString(o);
        } catch (JsonProcessingException e) {
            log.warn("toJson error:{}", e.getMessage());
        }
        return null;
    }

    /**
     * Like {@link #toJson(Object)} but via {@link #strictObjectMapper}: numbers stay numbers.
     * See that field's javadoc for why this matters for outgoing third-party API request bodies.
     */
    public static String toJsonStrict(Object o) {
        try {
            return strictObjectMapper.writeValueAsString(o);
        } catch (JsonProcessingException e) {
            log.warn("toJsonStrict error:{}", e.getMessage());
        }
        return null;
    }

    public static <T> T fromJson(String json, Class<T> classOfT) {
        try {
            return objectMapper.readValue(json, classOfT);
        } catch (IOException e) {
            log.warn("fromJson error:{}", e.getMessage());
        }
        return null;
    }

    public static <T> List<T> fromJson(String json, TypeReference<List<T>> typeRef) {
        try {
            return objectMapper.readValue(json, typeRef);
        } catch (IOException e) {
            log.warn("fromJson type error:{}", e.getMessage());
        }
        return null;
    }

    public static boolean isJson(String json) {
        if (StringUtils.isEmpty(json)) {
            return false;
        }
        try {
            JsonNode jsonNode = objectMapper.readTree(json);
            return jsonNode.isObject();
        } catch (IOException e) {
            log.warn("isJson error:{}", e.getMessage());
        }
        return false;
    }

    public static <D, M> D convert(M m, Class<D> dClass) {
        try {
            return objectMapper.readValue(objectMapper.writeValueAsString(m), dClass);
        } catch (JsonProcessingException e) {
            log.warn("convert error:{}", e.getMessage());
            throw new RuntimeException(e);
        }
    }

    public static Map<String, Object> toMap(Object o) {
        if (o == null) {
            return new HashMap<>();
        }
        try {
            return objectMapper.convertValue(o, new TypeReference<>() {});
        } catch (IllegalArgumentException e) {
            log.warn("toMap error:{}", e.getMessage());
            return new HashMap<>();
        }
    }

    public static String compactJson(String prettyJson) {
        try {
            Object jsonObject = objectMapper.readValue(prettyJson, Object.class);
            return objectMapper.writeValueAsString(jsonObject);
        } catch (JsonProcessingException e) {
            return "";
        }
    }

    public static JsonNode fromJson(String json) {
        try {
            return objectMapper.readTree(json);
        } catch (IOException e) {
            log.warn("fromJson readTree error:{}", e.getMessage());
            return null;
        }
    }
}