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

import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;

public class Iterator<T> {

    protected SynchronousQueue<T> queue = new SynchronousQueue<>();
    protected volatile Boolean isLast = false;
    protected Long offerTimeout = 60000L;
    protected Long pollTimeout = 60000L;
    protected Function<T, Boolean> isLastFunction;

    public Iterator(Function<T, Boolean> isLastFunction) {
        this.isLastFunction = isLastFunction;
    }

    public void append(T message) throws TimeoutException {
        try {
            boolean result = queue.offer(message, offerTimeout, TimeUnit.MILLISECONDS);
            if (!result) {
                throw new TimeoutException("offer message timeout");
            }
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

    public T next() throws TimeoutException {
        try {
            T chunk = queue.poll(pollTimeout, TimeUnit.MILLISECONDS);

            if (chunk == null) {
                isLast = true;
                throw new TimeoutException("poll message timeout");
            }

            if (isLastFunction.apply(chunk)) {
                isLast = true;
            }
            return chunk;
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

    public boolean hasNext() {
        return !isLast;
    }
}
