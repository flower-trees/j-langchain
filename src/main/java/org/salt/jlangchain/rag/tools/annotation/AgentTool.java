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

import java.lang.annotation.*;

/**
 * Marks a method as an agent tool.
 *
 * <p>Single-parameter methods receive the raw Action Input string directly.
 * Multi-parameter methods expect a JSON object as Action Input; each key maps
 * to the corresponding parameter name (camelCase or snake_case both accepted).
 *
 * <p>Parameter descriptions are resolved in the following priority order:
 * <ol>
 *   <li>{@link #params()} — inline {@link ParamDesc} entries on this annotation
 *       (use when the VO is from a third-party package and cannot be modified)</li>
 *   <li>{@link Param} on VO fields — when you own the VO class</li>
 *   <li>{@link Param} on method parameters — for simple primitive/String parameters</li>
 * </ol>
 *
 * <pre>{@code
 * // Own VO: put @Param on fields
 * @AgentTool("查询用户订单信息")
 * public String queryOrder(OrderQueryRequest request) { ... }
 *
 * // Third-party VO: use inline @ParamDesc
 * @AgentTool(
 *     value = "查询用户订单信息",
 *     params = {
 *         @ParamDesc(name = "orderId",   desc = "订单ID，格式：ORD-2024-XXXXXX"),
 *         @ParamDesc(name = "queryType", desc = "查询类型：LATEST / ALL，默认 LATEST")
 *     }
 * )
 * public String queryOrder(ThirdPartyOrderRequest request) { ... }
 * }</pre>
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface AgentTool {

    /** Tool description shown to the LLM. */
    String value();

    /**
     * Optional explicit tool name. Defaults to the method name converted to
     * snake_case (e.g. {@code getWeather} → {@code get_weather}).
     */
    String name() default "";

    /**
     * Inline parameter descriptions for third-party VOs whose fields cannot be
     * annotated with {@link Param}. Takes priority over field-level {@link Param}.
     */
    ParamDesc[] params() default {};
}
