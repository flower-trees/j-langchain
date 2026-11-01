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

package org.salt.jlangchain.rag.tools;

import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.Map;
import java.util.function.Function;

@EqualsAndHashCode(callSuper = true)
@Data
public class Tool extends BaseTool<Object, Object>{

    String name;
    String params;
    String description;
    Function<Object, Object> func;

    /**
     * Optional raw JSON Schema for this tool's parameters (the {@code inputSchema} an MCP server
     * hands back verbatim, or any hand-built schema). When present, {@code McpAgentExecutor}'s
     * {@code toAiTool()} sends this directly as the native function-calling {@code parameters}
     * field instead of re-deriving one from {@link #params} — {@code params} degrades a schema to
     * a flat {@code "name: Type"} string and its consumer, {@code buildSchema()}, marks every
     * listed name required regardless of the source schema's own {@code required} array, which
     * silently forces the model to supply values (including mutually-exclusive or genuinely
     * optional ones) it shouldn't have to. Set this whenever the real schema is available so the
     * model sees accurate types, descriptions, and required/optional status; {@link #params}
     * still drives whatever text-based tool listing renders elsewhere (e.g. {@code PromptTemplate}),
     * so keep setting it too — the two aren't redundant, they feed different consumers.
     */
    Map<String, Object> parametersSchema;

    private Tool(ToolBuilder builder) {
        this.name = builder.name;
        this.params = builder.params;
        this.description = builder.description;
        this.func = builder.func;
        this.parametersSchema = builder.parametersSchema;
    }

    public Tool() {
    }

    public static ToolBuilder builder() {
        return new ToolBuilder();
    }

    public static final class ToolBuilder {
        private String name;
        private String params;
        private String description;
        private Function<Object, Object> func;
        private Map<String, Object> parametersSchema;

        private ToolBuilder() {
        }

        public ToolBuilder name(String name) {
            this.name = name;
            return this;
        }

        public ToolBuilder params(String params) {
            this.params = params;
            return this;
        }

        public ToolBuilder description(String description) {
            this.description = description;
            return this;
        }

        public ToolBuilder func(Function<Object, Object> func) {
            this.func = func;
            return this;
        }

        /** See {@link Tool#parametersSchema}. */
        public ToolBuilder parametersSchema(Map<String, Object> parametersSchema) {
            this.parametersSchema = parametersSchema;
            return this;
        }

        public Tool build() {
            if (this.func == null) {
                throw new IllegalStateException("func must be provided");
            }
            return new Tool(this);
        }

        @Override
        public String toString() {
            return "Tool.ToolBuilder(name=" + this.name + ", params=" + this.params + ", description=" + this.description + ", func=" + this.func + ")";
        }
    }

    @Override
    public Object invoke(Object input) {
        return func.apply(input);
    }
}
