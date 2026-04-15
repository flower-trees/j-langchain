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
import lombok.Data;
import org.springframework.util.CollectionUtils;

import java.util.List;
import java.util.Properties;

@Data
public class StanfordNLPTextSplitter extends TextSplitter {

    String separator = "\n\n";

    public StanfordNLPTextSplitter() {
        super();
    }

    protected StanfordNLPTextSplitter(StanfordNLPTextSplitterBuilder<?, ?> builder) {
        super(builder);
        if (builder.separatorSet) {
            this.separator = builder.separatorValue;
        }
    }

    public static StanfordNLPTextSplitterBuilder<?, ?> builder() {
        return new StanfordNLPTextSplitterBuilderImpl();
    }

    public static abstract class StanfordNLPTextSplitterBuilder<C extends StanfordNLPTextSplitter, B extends StanfordNLPTextSplitterBuilder<C, B>> extends TextSplitterBuilder<C, B> {
        private String separatorValue;
        private boolean separatorSet;

        public B separator(String separator) {
            this.separatorValue = separator;
            this.separatorSet = true;
            return self();
        }

        @Override
        public String toString() {
            return "StanfordNLPTextSplitter.StanfordNLPTextSplitterBuilder(super=" + super.toString() + ", separatorValue=" + this.separatorValue + ")";
        }
    }

    private static final class StanfordNLPTextSplitterBuilderImpl extends StanfordNLPTextSplitterBuilder<StanfordNLPTextSplitter, StanfordNLPTextSplitterBuilderImpl> {
        private StanfordNLPTextSplitterBuilderImpl() {
        }

        @Override
        protected StanfordNLPTextSplitterBuilderImpl self() {
            return this;
        }

        @Override
        public StanfordNLPTextSplitter build() {
            return new StanfordNLPTextSplitter(this);
        }
    }

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
