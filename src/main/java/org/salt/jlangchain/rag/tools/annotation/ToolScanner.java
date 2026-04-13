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

package org.salt.jlangchain.rag.tools.annotation;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import lombok.extern.slf4j.Slf4j;
import org.salt.jlangchain.rag.tools.Tool;
import org.salt.jlangchain.utils.JsonUtil;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Scans an object for {@link AgentTool}-annotated methods and converts them into
 * {@link org.salt.jlangchain.rag.tools.Tool} instances ready for use with
 * {@link org.salt.jlangchain.core.agent.AgentExecutor}.
 *
 * <p>Single-parameter methods: Action Input is passed as a raw string.<br>
 * Multi-parameter methods: Action Input must be a JSON object; keys are the
 * camelCase parameter names (snake_case also accepted via Jackson).
 */
@Slf4j
public class ToolScanner {

    /**
     * Dedicated mapper for deserializing Action Input JSON into complex-type parameters.
     * Uses the default (camelCase) naming strategy so that fields like {@code fromCity}
     * are bound from JSON key "fromCity" rather than requiring "from_city".
     * Unknown properties are silently ignored for forward compatibility.
     */
    private static final ObjectMapper TOOL_MAPPER = JsonMapper.builder()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
            .enable(MapperFeature.ACCEPT_CASE_INSENSITIVE_PROPERTIES)
            .build();

    /**
     * Scan {@code toolsProvider} and return a list of {@link Tool} objects,
     * one per {@link AgentTool}-annotated method.
     */
    public static List<Tool> scan(Object toolsProvider) {
        List<Tool> result = new ArrayList<>();
        Class<?> clazz = toolsProvider.getClass();

        for (Method method : clazz.getMethods()) {
            AgentTool ann = method.getAnnotation(AgentTool.class);
            if (ann == null) continue;

            method.setAccessible(true);
            String toolName = ann.name().isEmpty() ? toSnakeCase(method.getName()) : ann.name();
            Parameter[] parameters = method.getParameters();

            String params;
            String description;
            java.util.function.Function<Object, Object> func;

            if (parameters.length == 1 && !isComplexType(parameters[0].getType())) {
                // ── Single primitive/String parameter: pass raw Action Input string directly ──
                String paramName = parameters[0].getName();
                String paramType = simpleTypeName(parameters[0].getType());
                params = paramName + ": " + paramType;
                // If @Param is present, use its description to narrow the Action Input hint,
                // preventing LLM from wrapping the value in JSON.
                Param singleParamAnn = parameters[0].getAnnotation(Param.class);
                if (singleParamAnn != null && !singleParamAnn.value().isBlank()) {
                    description = ann.value() + "\nAction Input: " + singleParamAnn.value();
                } else {
                    description = ann.value();
                }
                func = input -> {
                    String raw = input.toString().trim();
                    // If LLM still wraps a single value in JSON, extract the first field's value
                    if (raw.startsWith("{")) {
                        try {
                            JsonNode node = JsonUtil.fromJson(raw);
                            if (node != null && node.isObject() && node.fields().hasNext()) {
                                JsonNode val = node.get(paramName);
                                if (val == null) val = node.fields().next().getValue();
                                raw = val.asText();
                            }
                        } catch (Exception ignored) {}
                    }
                    return invokeMethod(toolsProvider, method, new Object[]{coerce(raw, parameters[0].getType())});
                };

            } else if (parameters.length == 1 && isComplexType(parameters[0].getType())) {
                // ── Single complex-type parameter: deserialize JSON into the object ──
                Class<?> paramType = parameters[0].getType();
                Param paramAnn = parameters[0].getAnnotation(Param.class);
                String paramDesc = paramAnn != null ? paramAnn.value() : "";
                ObjectSchema schema = buildObjectSchema(paramType);
                params = paramType.getSimpleName();
                StringBuilder schemaSb = new StringBuilder();
                if (!paramDesc.isBlank()) {
                    schemaSb.append("  ").append(paramDesc).append("\n");
                }
                schemaSb.append("  Input JSON keys (").append(paramType.getSimpleName()).append("):\n");
                schemaSb.append(schema.fieldLines());
                description = ann.value() + "\n" + schemaSb + "  Action Input format: JSON, e.g. " + schema.example();

                func = input -> {
                    String raw = input.toString().trim();
                    if (raw.startsWith("```")) {
                        raw = raw.replaceAll("^```[a-zA-Z]*\\n?", "").replaceAll("```$", "").trim();
                    }
                    return invokeMethod(toolsProvider, method, new Object[]{coerce(raw, paramType)});
                };

            } else {
                // ── Multi-parameter: expect JSON object as Action Input ──
                StringBuilder paramsSb = new StringBuilder();
                StringBuilder schemaSb = new StringBuilder();
                schemaSb.append("  Input JSON keys:\n");
                for (int i = 0; i < parameters.length; i++) {
                    Parameter p = parameters[i];
                    Param paramAnn = p.getAnnotation(Param.class);
                    String paramDesc = paramAnn != null ? paramAnn.value() : "";
                    String pName = p.getName();
                    String pType = simpleTypeName(p.getType());
                    if (i > 0) paramsSb.append(", ");
                    paramsSb.append(pName).append(": ").append(pType);
                    schemaSb.append("    - ").append(pName).append(" (").append(pType).append("): ").append(paramDesc).append("\n");
                }
                // Build a one-line JSON example so LLM knows the expected format
                StringBuilder exampleSb = new StringBuilder("{");
                for (int i = 0; i < parameters.length; i++) {
                    if (i > 0) exampleSb.append(", ");
                    exampleSb.append("\"").append(parameters[i].getName()).append("\": ...");
                }
                exampleSb.append("}");
                params = paramsSb.toString();
                description = ann.value() + "\n" + schemaSb + "  Action Input format: JSON, e.g. " + exampleSb;

                Parameter[] paramsCopy = parameters;
                func = input -> {
                    String raw = input.toString().trim();
                    // strip markdown code fence if LLM wraps it
                    if (raw.startsWith("```")) {
                        raw = raw.replaceAll("^```[a-zA-Z]*\\n?", "").replaceAll("```$", "").trim();
                    }
                    JsonNode node = JsonUtil.fromJson(raw);
                    if (node == null || !node.isObject()) {
                        throw new RuntimeException("Tool '" + toolName + "' expects JSON input, got: " + raw);
                    }
                    Object[] args = new Object[paramsCopy.length];
                    for (int i = 0; i < paramsCopy.length; i++) {
                        String key = paramsCopy[i].getName();
                        // try camelCase first, then snake_case fallback
                        JsonNode val = node.get(key);
                        if (val == null) val = node.get(toSnakeCase(key));
                        if (val == null) {
                            throw new RuntimeException("Missing key '" + key + "' in Action Input JSON: " + raw);
                        }
                        args[i] = coerce(val.asText(), paramsCopy[i].getType());
                    }
                    return invokeMethod(toolsProvider, method, args);
                };
            }

            result.add(Tool.builder()
                .name(toolName)
                .params(params)
                .description(description)
                .func(func)
                .build());
        }

        if (result.isEmpty()) {
            log.warn("ToolScanner: no @AgentTool methods found on {}", clazz.getName());
        }
        return result;
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    /** Returns true for non-primitive, non-String, non-wrapper types that should be JSON-deserialized. */
    private static boolean isComplexType(Class<?> type) {
        return !type.isPrimitive()
            && type != String.class
            && !Number.class.isAssignableFrom(type)
            && type != Boolean.class
            && !type.isEnum();
    }

    /** Holds generated schema text and a one-line JSON example for a complex type. */
    private record ObjectSchema(String fieldLines, String example) {}

    /**
     * Scans {@code type}'s declared fields for {@link Param} annotations and builds
     * a schema description + JSON example string for LLM guidance.
     */
    private static ObjectSchema buildObjectSchema(Class<?> type) {
        StringBuilder fieldsSb = new StringBuilder();
        StringBuilder exampleSb = new StringBuilder("{");
        boolean first = true;
        for (Field field : type.getDeclaredFields()) {
            if (java.lang.reflect.Modifier.isStatic(field.getModifiers())) continue;
            Param fieldAnn = field.getAnnotation(Param.class);
            String desc = fieldAnn != null ? fieldAnn.value() : field.getName();
            String fType = simpleTypeName(field.getType());
            fieldsSb.append("    - ").append(field.getName())
                    .append(" (").append(fType).append("): ").append(desc).append("\n");
            if (!first) exampleSb.append(", ");
            exampleSb.append("\"").append(field.getName()).append("\": ...");
            first = false;
        }
        exampleSb.append("}");
        return new ObjectSchema(fieldsSb.toString(), exampleSb.toString());
    }

    private static Object invokeMethod(Object instance, Method method, Object[] args) {
        try {
            Object ret = method.invoke(instance, args);
            return ret != null ? ret.toString() : "";
        } catch (Exception e) {
            throw new RuntimeException("Tool method invocation failed: " + method.getName(), e);
        }
    }

    /** Convert camelCase to snake_case, e.g. getWeather → get_weather. */
    static String toSnakeCase(String camel) {
        return camel.replaceAll("([a-z])([A-Z])", "$1_$2").toLowerCase();
    }

    private static String simpleTypeName(Class<?> type) {
        Map<Class<?>, String> names = Map.of(
            String.class, "String",
            Integer.class, "int", int.class, "int",
            Long.class, "long", long.class, "long",
            Double.class, "double", double.class, "double",
            Boolean.class, "boolean", boolean.class, "boolean"
        );
        return names.getOrDefault(type, type.getSimpleName());
    }

    /** Best-effort coercion from string to the required parameter type. */
    private static Object coerce(String value, Class<?> type) {
        if (type == String.class) return value;
        if (type == int.class || type == Integer.class) return Integer.parseInt(value.trim());
        if (type == long.class || type == Long.class) return Long.parseLong(value.trim());
        if (type == double.class || type == Double.class) return Double.parseDouble(value.trim());
        if (type == boolean.class || type == Boolean.class) return Boolean.parseBoolean(value.trim());
        if (isComplexType(type)) {
            try {
                return TOOL_MAPPER.readValue(value, type);
            } catch (Exception e) {
                throw new RuntimeException("Failed to deserialize Action Input into " + type.getSimpleName() + ": " + value, e);
            }
        }
        return value;
    }
}
