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

package org.salt.jlangchain.rag.vector;

import lombok.Getter;
import lombok.Setter;
import org.salt.jlangchain.rag.embedding.Embeddings;
import org.salt.jlangchain.rag.media.Document;
import org.salt.jlangchain.utils.SnowUtil;

import java.util.List;
import java.util.Map;
import java.util.function.Function;

@Getter
@Setter
public abstract class VectorStore {

    protected Embeddings embeddingFunction;
    protected Function<Void, Long> idGenerator = (v) -> SnowUtil.next();

    public abstract List<Long> addText(List<String> tests, List<Map<String, Object>> metadatas, List<Long> ids, Long fileId);
    public abstract List<Long> addDocument(List<Document> documents, Long fileId);
    public abstract boolean delete(List<Long> ids);
    public abstract List<Document> getByIds(List<Long> ids);

    public abstract List<Document> similaritySearch(String query, int k);

    public abstract BaseRetriever asRetriever();
}