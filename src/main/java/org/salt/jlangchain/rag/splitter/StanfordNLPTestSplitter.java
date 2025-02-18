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

package org.salt.jlangchain.rag.splitter;

import edu.stanford.nlp.pipeline.CoreDocument;
import edu.stanford.nlp.pipeline.CoreSentence;
import edu.stanford.nlp.pipeline.StanfordCoreNLP;
import org.springframework.util.CollectionUtils;

import java.util.List;
import java.util.Properties;

public class StanfordNLPTestSplitter extends TestSplitter {

    String separator = "\n\n";

    @Override
    public List<String> splitText(String text) {

        Properties props = new Properties();
        props.setProperty("annotators", "tokenize, ssplit");
        StanfordCoreNLP pipeline = new StanfordCoreNLP(props);

        CoreDocument doc = new CoreDocument(text);
        pipeline.annotate(doc);

        List<CoreSentence> sentences = doc.sentences();

        if (!CollectionUtils.isEmpty(sentences)) {
            List<String> texts = sentences.stream().map(CoreSentence::text).toList();
            return mergeSplits(texts, separator);
        }

        return List.of();
    }
}