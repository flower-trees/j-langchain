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

public class InvokeRunnable<O, I> extends BaseRunnable<O, I> {

    private BaseRunnable<O, I> runnable;

    public InvokeRunnable(BaseRunnable<O, I> runnable) {
        this.runnable = runnable;
    }

    @Override
    public O invoke(I input) {
        return runnable.invoke(input);
    }

    @Override
    public O stream(I input) {
        return invoke(input);
    }
}
