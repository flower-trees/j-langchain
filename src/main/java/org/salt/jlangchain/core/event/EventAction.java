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
import java.util.function.Function;

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

    public void eventStart(Object input, Map<String, Object> config) {
        eventStart(input, config, Map.of());

    }

    public void eventStart(Object input, Map<String, Object> config, Map<String, Object> metadata) {
        if (isHasEvent()) {
            EventMessageChunk eventMessageChunk = getContextBus().getTransmit(CallInfo.EVENT_MESSAGE_CHUNK.name());
            EventMessageChunk event = EventMessageChunk.builder()
                    .type(eventName)
                    .event(eventType.get(eventName).get("start"))
                    .data(input != null ? Map.of("input", input) : Map.of())
                    .name(config.get("run_name") != null && config.get("run_name") instanceof String ? (String) config.get("run_name") : this.getClass().getSimpleName())
                    .tags(config.get("tags") != null && config.get("tags") instanceof List ? (List<String>) config.get("tags") : List.of())
                    .metadata(metadata)
                    .runId(getRunId())
                    .parentIds(getContextBus() != null && !CollectionUtils.isEmpty(getContextBus().getPreRunIds()) ? getContextBus().getPreRunIds() : List.of())
                    .build();
            if (isFilterThrough(event)) {
                eventMessageChunk.asynAppend(event);
            }
        }
    }

    public void eventStream(Object chunk, Map<String, Object> config) {
        eventStream(chunk, config, Map.of());
    }

    public void eventStream(Object chunk, Map<String, Object> config, Map<String, Object> metadata) {
        if (isHasEvent()) {
            EventMessageChunk eventMessageChunk = getContextBus().getTransmit(CallInfo.EVENT_MESSAGE_CHUNK.name());
            EventMessageChunk event = EventMessageChunk.builder()
                    .type(eventName)
                    .event(eventType.get(eventName).get("stream"))
                    .data(chunk != null ? Map.of("chunk", chunk) : Map.of())
                    .name(config.get("run_name") != null && config.get("run_name") instanceof String ? (String) config.get("run_name") : this.getClass().getSimpleName())
                    .tags(config.get("tags") != null && config.get("tags") instanceof List ? (List<String>) config.get("tags") : List.of())
                    .metadata(metadata)
                    .runId(getRunId())
                    .parentIds(getContextBus() != null && !CollectionUtils.isEmpty(getContextBus().getPreRunIds()) ? getContextBus().getPreRunIds() : List.of())
                    .build();
            if (isFilterThrough(event)) {
                eventMessageChunk.asynAppend(event);
            }
        }
    }

    public void eventEnd(Object output, Map<String, Object> config) {
        eventEnd(output, config, false, Map.of());
    }

    public void eventEnd(Object output, Map<String, Object> config, Boolean isLast) {
        eventEnd(output, config, isLast, Map.of());
    }

    public void eventEnd(Object output, Map<String, Object> config, Map<String, Object> metadata) {
        eventEnd(output, config, false, Map.of());
    }

    public void eventEnd(Object output, Map<String, Object> config, Boolean isLast, Map<String, Object> metadata) {
        if (isHasEvent()) {
            EventMessageChunk eventMessageChunk = getContextBus().getTransmit(CallInfo.EVENT_MESSAGE_CHUNK.name());
            EventMessageChunk event = EventMessageChunk.builder()
                    .type(eventName)
                    .event(eventType.get(eventName).get("end"))
                    .data(output instanceof IteratorAction<?> ? Map.of("output", ((IteratorAction<?>) output).getCumulate().toString()) : output != null ? Map.of("output", output) : Map.of("output", Map.of()))
                    .name(config.get("run_name") != null && config.get("run_name") instanceof String ? (String) config.get("run_name") : this.getClass().getSimpleName())
                    .tags(config.get("tags") != null && config.get("tags") instanceof List ? (List<String>) config.get("tags") : List.of())
                    .metadata(metadata)
                    .runId(getRunId())
                    .parentIds(getContextBus() != null && !CollectionUtils.isEmpty(getContextBus().getPreRunIds()) ? getContextBus().getPreRunIds() : List.of())
                    .build();
            if (getContextBus().getTransmit(CallInfo.EVENT_CHAIN.name()) == null
                    || !(boolean) getContextBus().getTransmit(CallInfo.EVENT_CHAIN.name())
                    || (isLast != null && isLast)) {
                event.setLast(true);
            }
            if (isFilterThrough(event)) {
                eventMessageChunk.asynAppend(event);
            } else {
                if (event.isLast()) {
                    eventMessageChunk.asynAppend(EventMessageChunk.builder().isRest(true).build());
                }
            }
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

    protected String getRunId() {
        return getContextBus() != null ? getContextBus().getRunId(getContextBus().getNodeIdOrAlias()) : null;
    }

    private boolean isFilterThrough(EventMessageChunk event) {
        if (getContextBus() != null && getContextBus().getTransmit(CallInfo.EVENT_FILTER.name()) != null) {
            Function<EventMessageChunk, Boolean> filter = getContextBus().getTransmit(CallInfo.EVENT_FILTER.name());
            return filter.apply(event);
        }
        return true;
    }
}
