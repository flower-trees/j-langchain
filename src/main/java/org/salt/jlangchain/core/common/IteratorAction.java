package org.salt.jlangchain.core.common;

import org.salt.function.flow.thread.TheadHelper;
import org.salt.jlangchain.utils.JsonUtil;
import org.salt.jlangchain.utils.SpringContextUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

public interface IteratorAction<T> {

    Logger log = LoggerFactory.getLogger(IteratorAction.class);

    Iterator<T> getIterator();

    default void ignore() {
        ignore(null);
    }

    default void ignore(Consumer<T> callback) {
        SpringContextUtil.getApplicationContext().getBean(TheadHelper.class).submit(() -> {
            while (getIterator().hasNext()) {
                try {
                    log.debug("ignore message: {}", JsonUtil.toJson(getIterator().next()));
                } catch (TimeoutException e) {
                    throw new RuntimeException(e);
                }
            }
            if (callback != null) {
                callback.accept(null);
            }
        });
    }

    default void asynAppend(T t) {
        SpringContextUtil.getApplicationContext().getBean(TheadHelper.class).submit(() -> {
            try {
                getIterator().append(t);
            } catch (TimeoutException e) {
                log.warn("asynAppend error", e);
                throw new RuntimeException(e);
            }
        });
    }
}
