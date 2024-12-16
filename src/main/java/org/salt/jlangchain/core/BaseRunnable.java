package org.salt.jlangchain.core;

import lombok.Data;
import lombok.EqualsAndHashCode;
import org.salt.function.flow.node.FlowNode;
import org.salt.jlangchain.core.common.CallInfo;

@EqualsAndHashCode(callSuper = true)
@Data
public abstract class BaseRunnable<O, I> extends FlowNode<O, I> {

    protected boolean isStream;

    public O process(I input) {
        before();

        if (getContextBus().getTransmit(CallInfo.STREAM.name()) != null) {
            isStream = getContextBus().getTransmit(CallInfo.STREAM.name());
        }

        if (this.isStream) {
            return stream(input);
        } else {
            return invoke(input);
        }
    }

    public void before() {
    }

    public O stream(I input) {
        return invoke(input);
    }

    public abstract O invoke(I input);
}
