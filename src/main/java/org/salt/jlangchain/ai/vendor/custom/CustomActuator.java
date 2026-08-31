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

package org.salt.jlangchain.ai.vendor.custom;

import org.salt.jlangchain.ai.chat.openai.OpenAIActuator;
import org.salt.jlangchain.ai.client.stream.HttpStreamClient;
import org.springframework.beans.factory.annotation.Value;

/**
 * Generic OpenAI-Chat-Completions-compatible vendor slot for any endpoint not covered by one of
 * the named vendor classes — OpenRouter, Together, Groq, a self-hosted vLLM/llama.cpp server, a
 * corporate LLM gateway, etc. Reuses {@link OpenAIActuator} (and its {@code OpenAIRequest}/
 * {@code OpenAIResponse} DTOs) unchanged, since request/response shape is what these gateways
 * standardize on; only the URL and key genuinely vary per deployment.
 * <p>
 * Unlike every other vendor actuator, {@code chat-url} has no sensible built-in default — there
 * is no one "custom" endpoint to point at. Left unset, requests go to a blank URL and fail
 * immediately with a clear connection error, rather than silently reaching some other vendor's
 * real API (which is what would happen if this reused, say, {@code ChatGPTActuator}'s
 * {@code api.openai.com} default).
 * <p>
 * Configure via {@code models.custom.chat-url} / {@code models.custom.chat-key} (Spring
 * property, settable as a {@code -D} system property or, in regnexe-cli, via
 * {@code model.chat_url} / {@code model.api_key} in {@code config.yml} with {@code vendor: custom}
 * — see {@code CliMain}'s startup wiring). {@code CUSTOM_KEY} is the conventional env var name
 * for the key, mirroring every other vendor's {@code <VENDOR>_KEY} convention.
 */
public class CustomActuator extends OpenAIActuator {

    @Value("${models.custom.chat-url:}")
    private String chatUrl;

    @Value("${models.custom.embedding-url:${models.custom.chat-url:}}")
    private String embeddingUrl;

    @Value("${models.custom.chat-key:${CUSTOM_KEY:}}")
    private String chatKey;

    public CustomActuator(HttpStreamClient commonHttpClient) {
        super(commonHttpClient);
    }

    @Override
    protected String getChatUrl() {
        return chatUrl;
    }

    @Override
    protected String getEmbeddingUrl() {
        return embeddingUrl;
    }

    @Override
    protected String getChatKey() {
        return chatKey;
    }
}
