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

package org.salt.jlangchain.rag.tools.mcp.tool;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import java.util.List;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class ToolResult {
    public List<ToolContent> content;
    public boolean isError;

    public ToolResult() {
    }

    public ToolResult(List<ToolContent> content) {
        this.content = content;
        this.isError = false;
    }

    public static ToolResult error(String message) {
        ToolResult result = new ToolResult();
        result.isError = true;
        result.content = List.of(new ToolContent("text", message));
        return result;
    }
}