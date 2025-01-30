package org.salt.jlangchain.core;

import lombok.Data;
import lombok.EqualsAndHashCode;
import org.salt.function.flow.node.FlowNode;
import org.salt.jlangchain.core.common.CallInfo;
import org.salt.jlangchain.core.event.EventMessageChunk;

@EqualsAndHashCode(callSuper = true)
@Data
public abstract class BaseRunnable<O, I> extends FlowNode<O, I> {

    protected boolean isStream;

    public O process(I input) {

        if (getContextBus().getTransmit(CallInfo.STREAM.name()) != null) {
            isStream = getContextBus().getTransmit(CallInfo.STREAM.name());
        }

        if (this.isStream) {
            return stream(input);
        } else {
            return invoke(input);
        }
    }

    public O stream(I input) {
        return invoke(input);
    }

    public EventMessageChunk streamEvent(I input) {
        throw new UnsupportedOperationException("streamEvent not implemented");
    }

    public abstract O invoke(I input);

    protected String getRunId() {
        return getContextBus() != null ? getContextBus().getRunId(getContextBus().getNodeIdOrAlias()) : null;
    }
}
