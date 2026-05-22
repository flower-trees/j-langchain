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

package org.salt.jlangchain.rag.tools.mcp.server.config;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;
import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = true)
public class ServerConfig {
    public String command;
    public List<String> args;
    public Map<String, String> env;
    
    public Type type;
    public String url;

    public Integer connectTimeoutMs;
    public Integer readTimeoutMs;
    public Integer writeTimeoutMs;
    public Integer callTimeoutMs;
    public Integer endpointTimeoutMs;
    public Integer requestTimeoutMs;

    public enum Type {
        stdio, sse, http
    }

    public int connectTimeoutMsOr(int defaultValue) {
        return connectTimeoutMs != null ? connectTimeoutMs : defaultValue;
    }

    public int readTimeoutMsOr(int defaultValue) {
        return readTimeoutMs != null ? readTimeoutMs : defaultValue;
    }

    public int writeTimeoutMsOr(int defaultValue) {
        return writeTimeoutMs != null ? writeTimeoutMs : defaultValue;
    }

    public int callTimeoutMsOr(int defaultValue) {
        return callTimeoutMs != null ? callTimeoutMs : defaultValue;
    }

    public int endpointTimeoutMsOr(int defaultValue) {
        return endpointTimeoutMs != null ? endpointTimeoutMs : defaultValue;
    }

    public int requestTimeoutMsOr(int defaultValue) {
        return requestTimeoutMs != null ? requestTimeoutMs : defaultValue;
    }
}
