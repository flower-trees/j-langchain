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
 * Inline parameter description used inside {@link AgentTool#params()}.
 *
 * <p>Use this when the parameter VO comes from a third-party package and
 * {@link Param} cannot be added to its fields directly:
 *
 * <pre>{@code
 * @AgentTool(
 *     value = "查询用户订单信息",
 *     params = {
 *         @ParamDesc(name = "orderId",   desc = "订单ID，格式：ORD-2024-XXXXXX"),
 *         @ParamDesc(name = "userId",    desc = "用户ID，格式：USR-XXXXXX"),
 *         @ParamDesc(name = "queryType", desc = "查询类型：LATEST / ALL，默认 LATEST")
 *     }
 * )
 * public String queryOrder(ThirdPartyOrderRequest request) { ... }
 * }</pre>
 */
@Target({})
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface ParamDesc {

    /** Field name in the VO class. */
    String name();

    /** Human-readable description shown to the LLM. */
    String desc();
}
