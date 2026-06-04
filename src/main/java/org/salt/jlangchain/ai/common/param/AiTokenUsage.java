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

package org.salt.jlangchain.ai.common.param;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Normalized token usage returned by model providers or accumulated by agents.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class AiTokenUsage {

    public static final String METADATA_KEY = "tokenUsage";

    private long promptTokens;
    private long completionTokens;
    private long totalTokens;
    private long cachedTokens;
    private long reasoningTokens;
    private long llmCalls;
    private long toolCalls;
    private String provider;
    private String model;
    private boolean estimated;

    public static AiTokenUsage empty() {
        return new AiTokenUsage();
    }

    public AiTokenUsage copy() {
        return new AiTokenUsage(promptTokens, completionTokens, totalTokens, cachedTokens,
                reasoningTokens, llmCalls, toolCalls, provider, model, estimated);
    }

    public void add(AiTokenUsage other) {
        if (other == null) return;
        this.promptTokens += other.promptTokens;
        this.completionTokens += other.completionTokens;
        this.totalTokens += other.totalTokens;
        this.cachedTokens += other.cachedTokens;
        this.reasoningTokens += other.reasoningTokens;
        this.llmCalls += other.llmCalls;
        this.toolCalls += other.toolCalls;
        if (this.provider == null) this.provider = other.provider;
        if (this.model == null) this.model = other.model;
        this.estimated = this.estimated || other.estimated;
    }

    public void incrementLlmCalls() {
        this.llmCalls++;
    }

    public void addToolCalls(long count) {
        this.toolCalls += Math.max(0, count);
    }
}
