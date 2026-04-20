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

    public EventMessageChunk() {
        super();
    }

    public EventMessageChunk(String type, String event, Map<String, Object> data, String name, String runId, List<String> parentIds, Map<String, Object> metadata, List<String> tags, boolean isLast, boolean isRest, Iterator<EventMessageChunk> iterator) {
        this.type = type;
        this.event = event;
        this.data = data;
        this.name = name;
        this.runId = runId;
        this.parentIds = parentIds;
        this.metadata = metadata;
        this.tags = tags;
        this.isLast = isLast;
        this.isRest = isRest;
        this.iterator = iterator != null ? iterator : new Iterator<>(this::isLast);
    }

    public static EventMessageChunkBuilder builder() {
        return new EventMessageChunkBuilder();
    }

    public static final class EventMessageChunkBuilder {
        private String type;
        private String event;
        private Map<String, Object> data;
        private String name;
        private String runId;
        private List<String> parentIds;
        private Map<String, Object> metadata;
        private List<String> tags;
        private boolean isLast;
        private boolean isRest;
        private Iterator<EventMessageChunk> iterator;

        private EventMessageChunkBuilder() {
        }

        public EventMessageChunkBuilder type(String type) {
            this.type = type;
            return this;
        }

        public EventMessageChunkBuilder event(String event) {
            this.event = event;
            return this;
        }

        public EventMessageChunkBuilder data(Map<String, Object> data) {
            this.data = data;
            return this;
        }

        public EventMessageChunkBuilder name(String name) {
            this.name = name;
            return this;
        }

        public EventMessageChunkBuilder runId(String runId) {
            this.runId = runId;
            return this;
        }

        public EventMessageChunkBuilder parentIds(List<String> parentIds) {
            this.parentIds = parentIds;
            return this;
        }

        public EventMessageChunkBuilder metadata(Map<String, Object> metadata) {
            this.metadata = metadata;
            return this;
        }

        public EventMessageChunkBuilder tags(List<String> tags) {
            this.tags = tags;
            return this;
        }

        public EventMessageChunkBuilder isLast(boolean isLast) {
            this.isLast = isLast;
            return this;
        }

        public EventMessageChunkBuilder isRest(boolean isRest) {
            this.isRest = isRest;
            return this;
        }

        public EventMessageChunkBuilder iterator(Iterator<EventMessageChunk> iterator) {
            this.iterator = iterator;
            return this;
        }

        public EventMessageChunk build() {
            return new EventMessageChunk(this.type, this.event, this.data, this.name, this.runId, this.parentIds, this.metadata, this.tags, this.isLast, this.isRest, this.iterator);
        }

        @Override
        public String toString() {
            return "EventMessageChunk.EventMessageChunkBuilder(type=" + this.type + ", event=" + this.event + ", data=" + this.data + ", name=" + this.name + ", runId=" + this.runId + ", parentIds=" + this.parentIds + ", metadata=" + this.metadata + ", tags=" + this.tags + ", isLast=" + this.isLast + ", isRest=" + this.isRest + ", iterator=" + this.iterator + ")";
        }
    }

    @Override
    public boolean isRest() {
        return isRest;
    }
}
