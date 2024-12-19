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

import org.salt.function.flow.FlowEngine;
import org.salt.function.flow.FlowInstance;
import org.salt.jlangchain.core.common.CallInfo;

import java.util.Map;

public class ChainActor {

    FlowEngine flowEngine;

    public ChainActor(FlowEngine flowEngine) {
        this.flowEngine = flowEngine;
    }

    public <O, I> O invoke(FlowInstance flow, I input) {
        Map<String, Object> callInfo = Map.of(CallInfo.STREAM.name(), false);
        return flowEngine.execute(flow, input, callInfo);
    }

    public <O, I> O invoke(FlowInstance flow, I input, Map<String, Object> transmitMap) {
        Map<String, Object> callInfo = Map.of(CallInfo.STREAM.name(), false);
        transmitMap.putAll(callInfo);
        return flowEngine.execute(flow, input, transmitMap);
    }

    public <O, I> O stream(FlowInstance flow, I input) {
        Map<String, Object> callInfo = Map.of(CallInfo.STREAM.name(), true);
        return flowEngine.execute(flow, input, callInfo);
    }

    public <O, I> O stream(FlowInstance flow, I input, Map<String, Object> transmitMap) {
        Map<String, Object> callInfo = Map.of(CallInfo.STREAM.name(), true);
        transmitMap.putAll(callInfo);
        return flowEngine.execute(flow, input, transmitMap);
    }
}
