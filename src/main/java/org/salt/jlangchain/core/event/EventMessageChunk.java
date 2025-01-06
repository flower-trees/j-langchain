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

package org.salt.jlangchain.core.event;

import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.*;
import org.salt.jlangchain.core.Serializable;
import org.salt.jlangchain.core.common.Iterator;
import org.salt.jlangchain.core.common.IteratorAction;

import java.util.List;
import java.util.Map;

@Setter
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EventMessageChunk extends Serializable implements IteratorAction<EventMessageChunk> {

    @JsonIgnore
    String type;
    String event;
    Map<String, Object> data;
    String name;
    String runId;
    List<String> parentIds;
    Map<String, Object> metadata;
    List<String> tags;

    @JsonIgnore
    protected boolean isLast = false;
    protected boolean isRest = false;

    protected Iterator<EventMessageChunk> iterator = new Iterator<>(this::isLast);

    private boolean isLast(EventMessageChunk chunk) {
        return chunk.isLast() || chunk.isRest();
    }

    @Override
    public boolean isRest() {
        return isRest;
    }
}
