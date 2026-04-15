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

import java.util.function.Function;

@EqualsAndHashCode(callSuper = true)
@Data
public class Tool extends BaseTool<Object, Object>{

    String name;
    String params;
    String description;
    Function<Object, Object> func;

    private Tool(ToolBuilder builder) {
        this.name = builder.name;
        this.params = builder.params;
        this.description = builder.description;
        this.func = builder.func;
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
