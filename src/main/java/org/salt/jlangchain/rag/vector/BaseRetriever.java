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

import org.salt.jlangchain.core.BaseRunnable;
import org.salt.jlangchain.rag.media.Document;

import java.util.List;

public abstract class BaseRetriever extends BaseRunnable<List<Document>, String> {

    @Override
    public List<Document> invoke(String input) {
        return getRelevantDocuments(input);
    }

    public abstract List<Document> getRelevantDocuments(String input);
}