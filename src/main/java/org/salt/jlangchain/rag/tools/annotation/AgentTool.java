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
 * <pre>{@code
 * public class CityTools {
 *
 *     @AgentTool("获取城市天气信息，输入城市名称")
 *     public String getWeather(String location) { ... }
 *
 *     @AgentTool("订机票")
 *     public String bookFlight(
 *         @Param("出发城市") String fromCity,
 *         @Param("目的城市") String toCity,
 *         @Param("日期，格式 YYYY-MM-DD") String date) { ... }
 * }
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
}
