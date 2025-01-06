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

package org.salt.jlangchain.core.event;

import org.salt.function.flow.context.ContextBus;
import org.salt.function.flow.context.IContextBus;
import org.salt.jlangchain.core.common.CallInfo;
import org.salt.jlangchain.core.common.IteratorAction;
import org.springframework.util.CollectionUtils;

import java.util.List;
import java.util.Map;

public class EventAction {

    Map<String, Map<String, String>> eventType =
            Map.of(
            "chain", Map.of(
                        "start","on_chain_start",
                        "stream", "on_chain_stream",
                        "end","on_chain_end"
                ),
            "prompt", Map.of(
                        "start","on_prompt_start",
                        "stream", "on_prompt_stream",
                        "end","on_prompt_end"
                ),
            "llm", Map.of(
                        "start","on_llm_start",
                        "stream", "on_llm_stream",
                        "end","on_llm_end"
                ),
            "parser", Map.of(
                        "start","on_parser_start",
                        "stream", "on_parser_stream",
                        "end","on_parser_end"
                )
    );

    private String eventName;

    public EventAction(String eventName) {
        this.eventName = eventName;
    }

    public void eventStart(Object input, String runId, Map<String, Object> config) {
        if (isHasEvent()) {
            EventMessageChunk eventMessageChunk = getContextBus().getTransmit(CallInfo.EVENT_MESSAGE_CHUNK.name());
            eventMessageChunk.asynAppend(
                    EventMessageChunk.builder()
                            .event(eventType.get(eventName).get("start"))
                            .data(input != null ? Map.of("input", input) : Map.of())
                            .name(config.get("run_name") != null && config.get("run_name") instanceof String ? (String) config.get("run_name") : this.getClass().getSimpleName())
                            .tags(config.get("tags") != null && config.get("tags") instanceof List ? (List<String>) config.get("tags") : List.of())
                            .runId(runId)
                            .parentIds(getContextBus() != null && !CollectionUtils.isEmpty(getContextBus().getPreRunIds()) ? getContextBus().getPreRunIds() : List.of())
                            .build());
        }
    }

    public void eventStream(Object chunk, String runId, Map<String, Object> config) {
        if (isHasEvent()) {
            EventMessageChunk eventMessageChunk = getContextBus().getTransmit(CallInfo.EVENT_MESSAGE_CHUNK.name());
            eventMessageChunk.asynAppend(
                    EventMessageChunk.builder()
                            .event(eventType.get(eventName).get("stream"))
                            .data(chunk != null ? Map.of("chunk", chunk) : Map.of())
                            .name(config.get("run_name") != null && config.get("run_name") instanceof String ? (String) config.get("run_name") : this.getClass().getSimpleName())
                            .tags(config.get("tags") != null && config.get("tags") instanceof List ? (List<String>) config.get("tags") : List.of())
                            .runId(runId)
                            .parentIds(getContextBus() != null && !CollectionUtils.isEmpty(getContextBus().getPreRunIds()) ? getContextBus().getPreRunIds() : List.of())
                            .build());
        }
    }

    public void eventEnd(Object output, String runId, Map<String, Object> config) {
        eventEnd(output, runId, config, null);
    }

    public void eventEnd(Object output, String runId, Map<String, Object> config, Boolean isLast) {
        if (isHasEvent()) {
            EventMessageChunk eventMessageChunk = getContextBus().getTransmit(CallInfo.EVENT_MESSAGE_CHUNK.name());
            EventMessageChunk chunkEnd =
                    EventMessageChunk.builder()
                            .event(eventType.get(eventName).get("end"))
                            .data(output instanceof IteratorAction<?> ? Map.of("output", ((IteratorAction<?>) output).getCumulate().toString()) : output != null ? Map.of("output", output) : Map.of("output", Map.of()))
                            .name(config.get("run_name") != null && config.get("run_name") instanceof String ? (String) config.get("run_name") : this.getClass().getSimpleName())
                            .tags(config.get("tags") != null && config.get("tags") instanceof List ? (List<String>) config.get("tags") : List.of())
                            .runId(runId)
                            .parentIds(getContextBus() != null && !CollectionUtils.isEmpty(getContextBus().getPreRunIds()) ? getContextBus().getPreRunIds() : List.of())
                            .build();
            if ((!(boolean) getContextBus().getTransmit(CallInfo.EVENT_CHAIN.name())) || (isLast != null && isLast)) {
                chunkEnd.setLast(true);
            }
            eventMessageChunk.asynAppend(chunkEnd);
        }
    }

    private IContextBus getContextBus() {
        return ContextBus.get();
    }

    private boolean isHasEvent() {
        return getContextBus() != null
                && getContextBus().getTransmit(CallInfo.EVENT.name()) != null
                && ((Boolean) getContextBus().getTransmit(CallInfo.EVENT.name()))
                && getContextBus().getTransmit(CallInfo.EVENT_MESSAGE_CHUNK.name()) != null;
    }
}
