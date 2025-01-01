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
        ignore( null, callback);
    }

    default void ignore(Consumer<T> action, Consumer<T> callback) {
        SpringContextUtil.getApplicationContext().getBean(TheadHelper.class).submit(() -> {
            try {
                while (getIterator().hasNext()) {
                    try {
                        T t = getIterator().next();
                        log.debug("ignore message: {}", JsonUtil.toJson(t));
                        if (action != null) {
                            action.accept(t);
                        }
                    } catch (TimeoutException e) {
                        throw new RuntimeException(e);
                    }
                }
                if (callback != null) {
                    callback.accept((T) this);
                }
            } catch (Exception e) {
                log.warn("ignore error", e);
            }
        });
    }

    default void asynAppend(T t) {
        SpringContextUtil.getApplicationContext().getBean(TheadHelper.class).submit(() -> {
            try {
                getIterator().append(t);
            } catch (Exception e) {
                log.warn("asynAppend error", e);
            }
        });
    }

    default StringBuilder getCumulate() {
        return new StringBuilder();
    }
}
