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

package org.salt.jlangchain.core.common;

import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;

@Slf4j
public class Iterator<T> {

    protected SynchronousQueue<T> queue = new SynchronousQueue<>(true);
    protected volatile Boolean isLast = false;
    protected Long offerTimeout = 3000L;
    protected Long pollTimeout = 60000L;
    protected Function<T, Boolean> isLastFunction;
    protected T nextChunk;

    public Iterator(Function<T, Boolean> isLastFunction) {
        this.isLastFunction = isLastFunction;
    }

    public void append(T message) throws TimeoutException {
        // Once hasNext() reads a chunk that isLastFunction marks as final, it sets isLast=true and
        // will never call poll() again (see the short-circuit at the top of hasNext()). So any message
        // appended after that point is guaranteed to have no consumer waiting for it - this commonly
        // happens when a provider sends one more frame (e.g. a usage-only trailer) after finish_reason
        // is already STOP, before the transport-level stream actually closes. Drop it immediately
        // instead of blocking for offerTimeout and throwing; this changes nothing on the normal
        // consumption path, it just removes a wait that was guaranteed to fail anyway.
        if (isLast) {
            log.debug("iterator already finished, drop late message: {}", message);
            return;
        }
        try {
            boolean result = queue.offer(message, offerTimeout, TimeUnit.MILLISECONDS);
            if (!result) {
                throw new TimeoutException("offer message timeout");
            }
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

    public T next() {
        return nextChunk;
    }

    public boolean hasNext() throws TimeoutException {
        if (isLast) {
            return false;
        }

        try {
            T chunk = queue.poll(pollTimeout, TimeUnit.MILLISECONDS);
            if (chunk == null) {
                throw new RuntimeException("poll message timeout");
            }

            if (chunk instanceof IteratorAction<?> && ((IteratorAction<?>) chunk).isRest()) {
                return false;
            }

            if (isLastFunction.apply(chunk)) {
                isLast = true;
            }
            nextChunk = chunk;
            return true;
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
    }
}
