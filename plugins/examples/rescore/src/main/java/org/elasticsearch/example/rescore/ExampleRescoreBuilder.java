/*
 * Licensed to Elasticsearch under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.elasticsearch.example.rescore;

import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.Explanation;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.TopDocs;
import org.elasticsearch.common.Nullable;
import org.elasticsearch.common.ParseField;
import org.elasticsearch.common.io.stream.StreamInput;
import org.elasticsearch.common.io.stream.StreamOutput;
import org.elasticsearch.common.xcontent.ConstructingObjectParser;
import org.elasticsearch.common.xcontent.XContentBuilder;
import org.elasticsearch.common.xcontent.XContentParser;
import org.elasticsearch.index.fielddata.*;
import org.elasticsearch.index.query.QueryRewriteContext;
import org.elasticsearch.index.query.QueryShardContext;
import org.elasticsearch.search.rescore.RescoreContext;
import org.elasticsearch.search.rescore.Rescorer;
import org.elasticsearch.search.rescore.RescorerBuilder;

import java.io.IOException;
import java.util.Arrays;
import java.util.Iterator;
import java.util.Objects;
import java.util.Set;
import java.util.logging.Logger;

import static java.util.Collections.singletonList;
import static org.elasticsearch.common.xcontent.ConstructingObjectParser.constructorArg;
import static org.elasticsearch.common.xcontent.ConstructingObjectParser.optionalConstructorArg;

/**
 * Example rescorer that multiplies the score of the hit by some factor and doesn't resort them.
 */
public class ExampleRescoreBuilder extends RescorerBuilder<ExampleRescoreBuilder> {
    public static final String NAME = "example";

    private final float factor;
    private final String factorField;

    public ExampleRescoreBuilder(float factor, @Nullable String factorField) {
        this.factor = factor;
        this.factorField = factorField;
    }

    ExampleRescoreBuilder(StreamInput in) throws IOException {
        super(in);
        factor = in.readFloat();
        factorField = in.readOptionalString();
    }

    @Override
    protected void doWriteTo(StreamOutput out) throws IOException {
        out.writeFloat(factor);
        out.writeOptionalString(factorField);
    }

    @Override
    public String getWriteableName() {
        return NAME;
    }

    @Override
    public RescorerBuilder<ExampleRescoreBuilder> rewrite(QueryRewriteContext ctx) throws IOException {
        return this;
    }

    private static final ParseField FACTOR = new ParseField("factor");
    private static final ParseField FACTOR_FIELD = new ParseField("factor_field");
    @Override
    protected void doXContent(XContentBuilder builder, Params params) throws IOException {
        builder.field(FACTOR.getPreferredName(), factor);
        if (factorField != null) {
            builder.field(FACTOR_FIELD.getPreferredName(), factorField);
        }
    }

    private static final ConstructingObjectParser<ExampleRescoreBuilder, Void> PARSER = new ConstructingObjectParser<>(NAME,
            args -> new ExampleRescoreBuilder((float) args[0], (String) args[1]));
    static {
        PARSER.declareFloat(constructorArg(), FACTOR);
        PARSER.declareString(optionalConstructorArg(), FACTOR_FIELD);
    }
    public static ExampleRescoreBuilder fromXContent(XContentParser parser) {
        return PARSER.apply(parser, null);
    }

    @Override
    public RescoreContext innerBuildContext(int windowSize, QueryShardContext context) throws IOException {
        IndexFieldData<?> factorField =
                this.factorField == null ? null : context.getForField(context.fieldMapper(this.factorField));
        return new ExampleRescoreContext(windowSize, factor, factorField);
    }

    @Override
    public boolean equals(Object obj) {
        if (false == super.equals(obj)) {
            return false;
        }
        ExampleRescoreBuilder other = (ExampleRescoreBuilder) obj;
        return factor == other.factor
                && Objects.equals(factorField, other.factorField);
    }

    @Override
    public int hashCode() {
        return Objects.hash(super.hashCode(), factor, factorField);
    }

    float factor() {
        return factor;
    }

    @Nullable
    String factorField() {
        return factorField;
    }

    private static class ExampleRescoreContext extends RescoreContext {
        private final float factor;
        @Nullable
        private final IndexFieldData<?> factorField;

        ExampleRescoreContext(int windowSize, float factor, @Nullable IndexFieldData<?> factorField) {
            super(windowSize, ExampleRescorer.INSTANCE);
            this.factor = factor;
            this.factorField = factorField;
        }
    }

    private static class ExampleRescorer implements Rescorer {
        private static final ExampleRescorer INSTANCE = new ExampleRescorer();

        @Override
        public TopDocs rescore(TopDocs topDocs, IndexSearcher searcher, RescoreContext rescoreContext) throws IOException {
            ExampleRescoreContext context = (ExampleRescoreContext) rescoreContext;
            int end = Math.min(topDocs.scoreDocs.length, rescoreContext.getWindowSize());
            for (int i = 0; i < end; i++) {
                topDocs.scoreDocs[i].score *= context.factor;
            }
            if (context.factorField != null) {
                /*
                 * Since this example looks up a single field value it should
                 * access them in docId order because that is the order in
                 * which they are stored on disk and we want reads to be
                 * forwards and close together if possible.
                 *
                 * If accessing multiple fields we'd be better off accessing
                 * them in (reader, field, docId) order because that is the
                 * order they are on disk.
                 */
                ScoreDoc[] sortedByDocId = new ScoreDoc[topDocs.scoreDocs.length];
                System.arraycopy(topDocs.scoreDocs, 0, sortedByDocId, 0, topDocs.scoreDocs.length);
                Arrays.sort(sortedByDocId, (a, b) -> a.doc - b.doc); // Safe because doc ids >= 0
                Iterator<LeafReaderContext> leaves = searcher.getIndexReader().leaves().iterator();
                LeafReaderContext leaf = null;
                SortedNumericDoubleValues data = null;
                int currentReaderIx = -1;
                int endDoc = 0;
                for (int i = 0; i < end; i++) {
                    ScoreDoc hit = sortedByDocId[i];
                    while (hit.doc >= endDoc) {
                        currentReaderIx++;
                        leaf = searcher.getIndexReader().leaves().get(currentReaderIx);
                        endDoc = leaf.docBase + leaf.reader().maxDoc();
                    }
                    //计算相对位置
                    int docId = hit.doc - leaf.docBase;
                    //拿到facotrField指定字段的值
                    SortedBinaryDocValues values_test = ((ExampleRescoreContext) rescoreContext).factorField.load(leaf).getBytesValues();
                    values_test.advanceExact(docId);
                    String productname = "";
                    //内部会有分词，所以通过循环把所有字段内容都拿到
                    for(int docV=0;docV < values_test.docValueCount(); docV++){
                        productname +=values_test.nextValue().utf8ToString();
                    }
                    //输出测试
                    System.out.println("商品名称："+productname+" Id:"+hit.doc + " 得分是：" + hit.score);
                }
            }
            // Sort by score descending, then docID ascending, just like lucene's QueryRescorer
            Arrays.sort(topDocs.scoreDocs, (a, b) -> {
                if (a.score > b.score) {
                    return -1;
                }
                if (a.score < b.score) {
                    return 1;
                }
                // Safe because doc ids >= 0
                return a.doc - b.doc;
            });
            return topDocs;
        }

        @Override
        public Explanation explain(int topLevelDocId, IndexSearcher searcher, RescoreContext rescoreContext,
                Explanation sourceExplanation) throws IOException {
            ExampleRescoreContext context = (ExampleRescoreContext) rescoreContext;
            // Note that this is inaccurate because it ignores factor field
            return Explanation.match(context.factor, "test", singletonList(sourceExplanation));
        }

        @Override
        public void extractTerms(IndexSearcher searcher, RescoreContext rescoreContext, Set<Term> termsSet) {
            // Since we don't use queries there are no terms to extract.
        }
    }
}
