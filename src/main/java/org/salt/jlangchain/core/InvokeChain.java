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
import org.salt.jlangchain.utils.SpringContextUtil;

public class InvokeChain extends BaseRunnable<Object, Object> {

    private FlowInstance chain;

    public InvokeChain(FlowInstance chain) {
        this.chain = chain;
    }

    @Override
    public Object invoke(Object input) {
        Boolean isStream = getContextBus().getTransmit(CallInfo.STREAM.name());
        try {
            getContextBus().putTransmit(CallInfo.STREAM.name(), false);
            return SpringContextUtil.getApplicationContext().getBean(FlowEngine.class).execute(chain);
        } finally {
            if (isStream != null) {
                getContextBus().putTransmit(CallInfo.STREAM.name(), isStream);
            }
        }
    }

    @Override
    public Object stream(Object input) {
        return invoke(input);
    }
}
