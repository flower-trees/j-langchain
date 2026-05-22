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

    public Integer connectTimeoutSeconds;
    public Integer readTimeoutSeconds;
    public Integer writeTimeoutSeconds;
    public Integer endpointTimeoutSeconds;
    public Integer requestTimeoutSeconds;

    public enum Type {
        stdio, sse, http
    }

    public int connectTimeoutSecondsOr(int defaultValue) {
        return connectTimeoutSeconds != null ? connectTimeoutSeconds : defaultValue;
    }

    public int readTimeoutSecondsOr(int defaultValue) {
        return readTimeoutSeconds != null ? readTimeoutSeconds : defaultValue;
    }

    public int writeTimeoutSecondsOr(int defaultValue) {
        return writeTimeoutSeconds != null ? writeTimeoutSeconds : defaultValue;
    }

    public int endpointTimeoutSecondsOr(int defaultValue) {
        return endpointTimeoutSeconds != null ? endpointTimeoutSeconds : defaultValue;
    }

    public int requestTimeoutSecondsOr(int defaultValue) {
        return requestTimeoutSeconds != null ? requestTimeoutSeconds : defaultValue;
    }
}
