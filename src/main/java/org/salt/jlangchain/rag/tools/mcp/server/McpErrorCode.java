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

package org.salt.jlangchain.rag.tools.mcp.server;

public class McpErrorCode {
    public static final int PARSE_ERROR = -32700;        // 解析错误
    public static final int INVALID_REQUEST = -32600;    // 无效请求
    public static final int METHOD_NOT_FOUND = -32601;   // 方法不存在
    public static final int INVALID_PARAMS = -32602;     // 参数无效
    public static final int INTERNAL_ERROR = -32603;     // 内部错误
}