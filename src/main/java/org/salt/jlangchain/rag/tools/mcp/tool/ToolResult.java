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

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

@Data
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class ToolResult {
    public List<ToolContent> content;  // Content list (supports multiple types)
    public boolean isError;            // Whether it is an error result

    public ToolResult(List<ToolContent> content) {
        this.content = content;
        this.isError = false;
    }

    // Convenience constructor
    public static ToolResult error(String message) {
        ToolResult result = new ToolResult();
        result.isError = true;
        result.content = new ArrayList<>();
        result.content.add(new TextContent(message));
        return result;
    }

    public static ToolResult text(String text) {
        ToolResult result = new ToolResult();
        result.isError = false;
        result.content = new ArrayList<>();
        result.content.add(new TextContent(text));
        return result;
    }

    // Convenience getter methods
    @JsonIgnore
    public String getFirstText() {
        if (content == null || content.isEmpty()) {
            return null;
        }
        for (ToolContent c : content) {
            if (c instanceof TextContent) {
                return ((TextContent) c).text;
            }
        }
        return null;
    }

    @JsonIgnore
    public String getAllText() {
        if (content == null || content.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (ToolContent c : content) {
            if (c instanceof TextContent) {
                if (!sb.isEmpty()) {
                    sb.append("\n");
                }
                sb.append(((TextContent) c).text);
            }
        }
        return sb.toString();
    }
}