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

package org.salt.jlangchain.core;

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections.MapUtils;
import org.salt.function.flow.FlowEngine;
import org.salt.function.flow.FlowInstance;
import org.salt.jlangchain.core.common.CallInfo;
import org.salt.jlangchain.core.common.IteratorAction;
import org.salt.jlangchain.core.event.EventMessageChunk;

import java.util.HashMap;
import java.util.Map;

@Slf4j
public class ChainActor {

    FlowEngine flowEngine;

    public FlowEngine.Builder builder() {
        return flowEngine.builder();
    }

    public ChainActor(FlowEngine flowEngine) {
        this.flowEngine = flowEngine;
    }

    public <O, I> O invoke(FlowInstance flow, I input) {
        return invoke(flow, input, null);
    }

    public <O, I> O invoke(FlowInstance flow, I input, Map<String, Object> transmitMap) {
        Map<String, Object> paramMap = new HashMap<>();
        if (MapUtils.isNotEmpty(transmitMap)) {
            paramMap.putAll(transmitMap);
        }
        Map<String, Object> callInfo = Map.of(CallInfo.STREAM.name(), false);
        paramMap.putAll(callInfo);
        return flowEngine.execute(flow, input, paramMap);
    }

    public <O, I> O stream(FlowInstance flow, I input) {
        return stream(flow, input, null);
    }

    public <O, I> O stream(FlowInstance flow, I input, Map<String, Object> transmitMap) {
        Map<String, Object> paramMap = new HashMap<>();
        if (MapUtils.isNotEmpty(transmitMap)) {
            paramMap.putAll(transmitMap);
        }
        Map<String, Object> callInfo = Map.of(CallInfo.STREAM.name(), true);
        paramMap.putAll(callInfo);
        return flowEngine.execute(flow, input, paramMap);
    }

    public <I> EventMessageChunk streamEvent(FlowInstance flow, I input) {
        return streamEvent(flow, input, null);
    }

    public <I> EventMessageChunk streamEvent(FlowInstance flow, I input, Map<String, Object> transmitMap) {

        Map<String, Object> paramMap = new HashMap<>();
        if (MapUtils.isNotEmpty(transmitMap)) {
            paramMap.putAll(transmitMap);
        }

        EventMessageChunk eventMessageChunk = new EventMessageChunk();

        eventMessageChunk.asynAppend(EventMessageChunk.builder().event("on_chain_start").build());

        Map<String, Object> callInfo = Map.of(
                CallInfo.STREAM.name(), true,
                CallInfo.EVENT.name(), true,
                CallInfo.EVENT_CHAIN.name(), true,
                CallInfo.EVENT_MESSAGE_CHUNK.name(), eventMessageChunk);
        paramMap.putAll(callInfo);
        IteratorAction<?> iteratorAction = flowEngine.execute(flow, input, paramMap);
        iteratorAction.ignore(intput -> eventMessageChunk.asynAppend(EventMessageChunk.builder().event("on_chain_end").isLast(true).build()));

        return eventMessageChunk;
    }
}
