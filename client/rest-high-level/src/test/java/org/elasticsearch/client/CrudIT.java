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

package org.elasticsearch.client;

import org.elasticsearch.ElasticsearchException;
import org.elasticsearch.ElasticsearchStatusException;
import org.elasticsearch.action.ActionListener;
import org.elasticsearch.action.DocWriteRequest;
import org.elasticsearch.action.DocWriteResponse;
import org.elasticsearch.action.admin.cluster.node.tasks.list.ListTasksRequest;
import org.elasticsearch.action.admin.cluster.node.tasks.list.ListTasksResponse;
import org.elasticsearch.action.admin.cluster.node.tasks.list.TaskGroup;
import org.elasticsearch.action.bulk.BulkItemResponse;
import org.elasticsearch.action.bulk.BulkProcessor;
import org.elasticsearch.action.bulk.BulkRequest;
import org.elasticsearch.action.bulk.BulkResponse;
import org.elasticsearch.action.delete.DeleteRequest;
import org.elasticsearch.action.delete.DeleteResponse;
import org.elasticsearch.action.get.GetRequest;
import org.elasticsearch.action.get.GetResponse;
import org.elasticsearch.action.get.MultiGetRequest;
import org.elasticsearch.action.get.MultiGetResponse;
import org.elasticsearch.action.index.IndexRequest;
import org.elasticsearch.action.index.IndexResponse;
import org.elasticsearch.action.search.SearchRequest;
import org.elasticsearch.action.support.WriteRequest.RefreshPolicy;
import org.elasticsearch.action.update.UpdateRequest;
import org.elasticsearch.action.update.UpdateResponse;
import org.elasticsearch.client.core.MultiTermVectorsRequest;
import org.elasticsearch.client.core.MultiTermVectorsResponse;
import org.elasticsearch.client.core.TermVectorsRequest;
import org.elasticsearch.client.core.TermVectorsResponse;
import org.elasticsearch.client.indices.GetIndexRequest;
import org.elasticsearch.common.Strings;
import org.elasticsearch.common.bytes.BytesReference;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.unit.ByteSizeUnit;
import org.elasticsearch.common.unit.ByteSizeValue;
import org.elasticsearch.common.xcontent.XContentBuilder;
import org.elasticsearch.common.xcontent.XContentFactory;
import org.elasticsearch.common.xcontent.XContentType;
import org.elasticsearch.index.VersionType;
import org.elasticsearch.index.get.GetResult;
import org.elasticsearch.index.query.IdsQueryBuilder;
import org.elasticsearch.index.reindex.BulkByScrollResponse;
import org.elasticsearch.index.reindex.DeleteByQueryAction;
import org.elasticsearch.index.reindex.DeleteByQueryRequest;
import org.elasticsearch.index.reindex.UpdateByQueryAction;
import org.elasticsearch.index.reindex.UpdateByQueryRequest;
import org.elasticsearch.rest.RestStatus;
import org.elasticsearch.rest.action.document.RestBulkAction;
import org.elasticsearch.rest.action.document.RestDeleteAction;
import org.elasticsearch.rest.action.document.RestGetAction;
import org.elasticsearch.rest.action.document.RestIndexAction;
import org.elasticsearch.rest.action.document.RestMultiGetAction;
import org.elasticsearch.rest.action.document.RestUpdateAction;
import org.elasticsearch.script.Script;
import org.elasticsearch.script.ScriptType;
import org.elasticsearch.search.fetch.subphase.FetchSourceContext;
import org.elasticsearch.tasks.RawTaskStatus;
import org.elasticsearch.tasks.TaskId;
import org.joda.time.DateTime;
import org.joda.time.DateTimeZone;
import org.joda.time.format.DateTimeFormat;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static java.util.Collections.singletonMap;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.lessThan;

public class CrudIT extends ESRestHighLevelClientTestCase {

    public void testDelete() throws IOException {
        {
            // Testing deletion
            String docId = "id";
            IndexResponse indexResponse = highLevelClient().index(
                    new IndexRequest("index").id(docId).source(Collections.singletonMap("foo", "bar")), RequestOptions.DEFAULT);
            assertThat(indexResponse.getSeqNo(), greaterThanOrEqualTo(0L));
            DeleteRequest deleteRequest = new DeleteRequest("index", docId);
            if (randomBoolean()) {
                deleteRequest.setIfSeqNo(indexResponse.getSeqNo());
                deleteRequest.setIfPrimaryTerm(indexResponse.getPrimaryTerm());
            }
            DeleteResponse deleteResponse = execute(deleteRequest, highLevelClient()::delete, highLevelClient()::deleteAsync);
            assertEquals("index", deleteResponse.getIndex());
            assertEquals("_doc", deleteResponse.getType());
            assertEquals(docId, deleteResponse.getId());
            assertEquals(DocWriteResponse.Result.DELETED, deleteResponse.getResult());
        }
        {
            // Testing non existing document
            String docId = "does_not_exist";
            DeleteRequest deleteRequest = new DeleteRequest("index", docId);
            DeleteResponse deleteResponse = execute(deleteRequest, highLevelClient()::delete, highLevelClient()::deleteAsync);
            assertEquals("index", deleteResponse.getIndex());
            assertEquals("_doc", deleteResponse.getType());
            assertEquals(docId, deleteResponse.getId());
            assertEquals(DocWriteResponse.Result.NOT_FOUND, deleteResponse.getResult());
        }
        {
            // Testing version conflict
            String docId = "version_conflict";
            highLevelClient().index(
                    new IndexRequest("index").id( docId).source(Collections.singletonMap("foo", "bar")), RequestOptions.DEFAULT);
            DeleteRequest deleteRequest = new DeleteRequest("index", docId).setIfSeqNo(2).setIfPrimaryTerm(2);
            ElasticsearchException exception = expectThrows(ElasticsearchException.class,
                () -> execute(deleteRequest, highLevelClient()::delete, highLevelClient()::deleteAsync));
            assertEquals(RestStatus.CONFLICT, exception.status());
            assertEquals("Elasticsearch exception [type=version_conflict_engine_exception, reason=[" + docId + "]: " +
                "version conflict, required seqNo [2], primary term [2]. current document has seqNo [3] and primary term [1]]",
                exception.getMessage());
            assertEquals("index", exception.getMetadata("es.index").get(0));
        }
        {
            // Testing version type
            String docId = "version_type";
            highLevelClient().index(
                    new IndexRequest("index").id(docId).source(Collections.singletonMap("foo", "bar"))
                .versionType(VersionType.EXTERNAL).version(12), RequestOptions.DEFAULT);
            DeleteRequest deleteRequest = new DeleteRequest("index",  docId).versionType(VersionType.EXTERNAL).version(13);
            DeleteResponse deleteResponse = execute(deleteRequest, highLevelClient()::delete, highLevelClient()::deleteAsync);
            assertEquals("index", deleteResponse.getIndex());
            assertEquals("_doc", deleteResponse.getType());
            assertEquals(docId, deleteResponse.getId());
            assertEquals(DocWriteResponse.Result.DELETED, deleteResponse.getResult());
        }
        {
            // Testing version type with a wrong version
            String docId = "wrong_version";
            highLevelClient().index(
                    new IndexRequest("index").id(docId).source(Collections.singletonMap("foo", "bar"))
                .versionType(VersionType.EXTERNAL).version(12), RequestOptions.DEFAULT);
            ElasticsearchStatusException exception = expectThrows(ElasticsearchStatusException.class, () -> {
                DeleteRequest deleteRequest = new DeleteRequest("index",  docId).versionType(VersionType.EXTERNAL).version(10);
                execute(deleteRequest, highLevelClient()::delete, highLevelClient()::deleteAsync);
            });
            assertEquals(RestStatus.CONFLICT, exception.status());
            assertEquals("Elasticsearch exception [type=version_conflict_engine_exception, reason=[" +
                docId + "]: version conflict, current version [12] is higher or equal to the one provided [10]]", exception.getMessage());
            assertEquals("index", exception.getMetadata("es.index").get(0));
        }
        {
            // Testing routing
            String docId = "routing";
            highLevelClient().index(new IndexRequest("index").id(docId).source(Collections.singletonMap("foo", "bar")).routing("foo"),
                    RequestOptions.DEFAULT);
            DeleteRequest deleteRequest = new DeleteRequest("index",  docId).routing("foo");
            DeleteResponse deleteResponse = execute(deleteRequest, highLevelClient()::delete, highLevelClient()::deleteAsync);
            assertEquals("index", deleteResponse.getIndex());
            assertEquals("_doc", deleteResponse.getType());
            assertEquals(docId, deleteResponse.getId());
            assertEquals(DocWriteResponse.Result.DELETED, deleteResponse.getResult());
        }
    }

    public void testDeleteWithTypes() throws IOException {
        String docId = "id";
        IndexRequest indexRequest = new IndexRequest("index", "type", docId);
        indexRequest.source(Collections.singletonMap("foo", "bar"));
        execute(indexRequest,
            highLevelClient()::index,
            highLevelClient()::indexAsync,
            expectWarnings(RestIndexAction.TYPES_DEPRECATION_MESSAGE)
        );

        DeleteRequest deleteRequest = new DeleteRequest("index", "type", docId);
        DeleteResponse deleteResponse = execute(deleteRequest,
            highLevelClient()::delete,
            highLevelClient()::deleteAsync,
            expectWarnings(RestDeleteAction.TYPES_DEPRECATION_MESSAGE));

        assertEquals("index", deleteResponse.getIndex());
        assertEquals("type", deleteResponse.getType());
        assertEquals(docId, deleteResponse.getId());
        assertEquals(DocWriteResponse.Result.DELETED, deleteResponse.getResult());
    }

    public void testExists() throws IOException {
        {
            GetRequest getRequest = new GetRequest("index", "id");
            assertFalse(execute(getRequest, highLevelClient()::exists, highLevelClient()::existsAsync));
        }
        IndexRequest index = new IndexRequest("index").id("id");
        index.source("{\"field1\":\"value1\",\"field2\":\"value2\"}", XContentType.JSON);
        index.setRefreshPolicy(RefreshPolicy.IMMEDIATE);
        highLevelClient().index(index, RequestOptions.DEFAULT);
        {
            GetRequest getRequest = new GetRequest("index", "id");
            assertTrue(execute(getRequest, highLevelClient()::exists, highLevelClient()::existsAsync));
        }
        {
            GetRequest getRequest = new GetRequest("index", "does_not_exist");
            assertFalse(execute(getRequest, highLevelClient()::exists, highLevelClient()::existsAsync));
        }
        {
            GetRequest getRequest = new GetRequest("index", "does_not_exist").version(1);
            assertFalse(execute(getRequest, highLevelClient()::exists, highLevelClient()::existsAsync));
        }
    }

    public void testSourceExists() throws IOException {
        {
            GetRequest getRequest = new GetRequest("index", "id");
            assertFalse(execute(getRequest, highLevelClient()::existsSource, highLevelClient()::existsSourceAsync));
        }
        IndexRequest index = new IndexRequest("index").id("id");
        index.source("{\"field1\":\"value1\",\"field2\":\"value2\"}", XContentType.JSON);
        index.setRefreshPolicy(RefreshPolicy.IMMEDIATE);
        highLevelClient().index(index, RequestOptions.DEFAULT);
        {
            GetRequest getRequest = new GetRequest("index", "id");
            assertTrue(execute(getRequest, highLevelClient()::existsSource, highLevelClient()::existsSourceAsync));
        }
        {
            GetRequest getRequest = new GetRequest("index", "does_not_exist");
            assertFalse(execute(getRequest, highLevelClient()::existsSource, highLevelClient()::existsSourceAsync));
        }
        {
            GetRequest getRequest = new GetRequest("index", "does_not_exist").version(1);
            assertFalse(execute(getRequest, highLevelClient()::existsSource, highLevelClient()::existsSourceAsync));
        }
    }

    public void testSourceDoesNotExist() throws IOException {
        final String noSourceIndex = "no_source";
        {
            // Prepare
            Settings settings = Settings.builder()
                .put("number_of_shards", 1)
                .put("number_of_replicas", 0)
                .build();
            String mapping = "\"_source\": {\"enabled\": false}";
            createIndex(noSourceIndex, settings, mapping);
            assertEquals(
                RestStatus.OK,
                highLevelClient().bulk(
                    new BulkRequest()
                        .add(new IndexRequest(noSourceIndex).id("1")
                            .source(Collections.singletonMap("foo", 1), XContentType.JSON))
                        .add(new IndexRequest(noSourceIndex).id("2")
                            .source(Collections.singletonMap("foo", 2), XContentType.JSON))
                        .setRefreshPolicy(RefreshPolicy.IMMEDIATE),
                    RequestOptions.DEFAULT
                ).status()
            );
        }
        {
            GetRequest getRequest = new GetRequest(noSourceIndex, "1");
            assertTrue(execute(getRequest, highLevelClient()::exists, highLevelClient()::existsAsync));
            assertFalse(execute(getRequest, highLevelClient()::existsSource, highLevelClient()::existsSourceAsync));
        }
    }

    public void testGet() throws IOException {
        {
            GetRequest getRequest = new GetRequest("index", "id");
            ElasticsearchException exception = expectThrows(ElasticsearchException.class,
                    () -> execute(getRequest, highLevelClient()::get, highLevelClient()::getAsync));
            assertEquals(RestStatus.NOT_FOUND, exception.status());
            assertEquals("Elasticsearch exception [type=index_not_found_exception, reason=no such index [index]]", exception.getMessage());
            assertEquals("index", exception.getMetadata("es.index").get(0));
        }
        IndexRequest index = new IndexRequest("index").id("id");
        String document = "{\"field1\":\"value1\",\"field2\":\"value2\"}";
        index.source(document, XContentType.JSON);
        index.setRefreshPolicy(RefreshPolicy.IMMEDIATE);
        highLevelClient().index(index, RequestOptions.DEFAULT);
        {
            GetRequest getRequest = new GetRequest("index", "id").version(2);
            ElasticsearchException exception = expectThrows(ElasticsearchException.class,
                    () -> execute(getRequest, highLevelClient()::get, highLevelClient()::getAsync));
            assertEquals(RestStatus.CONFLICT, exception.status());
            assertEquals("Elasticsearch exception [type=version_conflict_engine_exception, " + "reason=[id]: " +
                    "version conflict, current version [1] is different than the one provided [2]]", exception.getMessage());
            assertEquals("index", exception.getMetadata("es.index").get(0));
        }
        {
            GetRequest getRequest = new GetRequest("index", "id");
            if (randomBoolean()) {
                getRequest.version(1L);
            }
            GetResponse getResponse = execute(getRequest, highLevelClient()::get, highLevelClient()::getAsync);
            assertEquals("index", getResponse.getIndex());
            assertEquals("_doc", getResponse.getType());
            assertEquals("id", getResponse.getId());
            assertTrue(getResponse.isExists());
            assertFalse(getResponse.isSourceEmpty());
            assertEquals(1L, getResponse.getVersion());
            assertEquals(document, getResponse.getSourceAsString());
        }
        {
            GetRequest getRequest = new GetRequest("index", "does_not_exist");
            GetResponse getResponse = execute(getRequest, highLevelClient()::get, highLevelClient()::getAsync);
            assertEquals("index", getResponse.getIndex());
            assertEquals("_doc", getResponse.getType());
            assertEquals("does_not_exist", getResponse.getId());
            assertFalse(getResponse.isExists());
            assertEquals(-1, getResponse.getVersion());
            assertTrue(getResponse.isSourceEmpty());
            assertNull(getResponse.getSourceAsString());
        }
        {
            GetRequest getRequest = new GetRequest("index", "id");
            getRequest.fetchSourceContext(new FetchSourceContext(false, Strings.EMPTY_ARRAY, Strings.EMPTY_ARRAY));
            GetResponse getResponse = execute(getRequest, highLevelClient()::get, highLevelClient()::getAsync);
            assertEquals("index", getResponse.getIndex());
            assertEquals("_doc", getResponse.getType());
            assertEquals("id", getResponse.getId());
            assertTrue(getResponse.isExists());
            assertTrue(getResponse.isSourceEmpty());
            assertEquals(1L, getResponse.getVersion());
            assertNull(getResponse.getSourceAsString());
        }
        {
            GetRequest getRequest = new GetRequest("index", "id");
            if (randomBoolean()) {
                getRequest.fetchSourceContext(new FetchSourceContext(true, new String[]{"field1"}, Strings.EMPTY_ARRAY));
            } else {
                getRequest.fetchSourceContext(new FetchSourceContext(true, Strings.EMPTY_ARRAY, new String[]{"field2"}));
            }
            GetResponse getResponse = execute(getRequest, highLevelClient()::get, highLevelClient()::getAsync);
            assertEquals("index", getResponse.getIndex());
            assertEquals("_doc", getResponse.getType());
            assertEquals("id", getResponse.getId());
            assertTrue(getResponse.isExists());
            assertFalse(getResponse.isSourceEmpty());
            assertEquals(1L, getResponse.getVersion());
            Map<String, Object> sourceAsMap = getResponse.getSourceAsMap();
            assertEquals(1, sourceAsMap.size());
            assertEquals("value1", sourceAsMap.get("field1"));
        }
    }

    public void testGetWithTypes() throws IOException {
        String document = "{\"field\":\"value\"}";
        IndexRequest indexRequest = new IndexRequest("index", "type", "id");
        indexRequest.source(document, XContentType.JSON);
        indexRequest.setRefreshPolicy(RefreshPolicy.IMMEDIATE);
        execute(indexRequest,
            highLevelClient()::index,
            highLevelClient()::indexAsync,
            expectWarnings(RestIndexAction.TYPES_DEPRECATION_MESSAGE)
        );

        GetRequest getRequest = new GetRequest("index", "type", "id");
        GetResponse getResponse = execute(getRequest,
            highLevelClient()::get,
            highLevelClient()::getAsync,
            expectWarnings(RestGetAction.TYPES_DEPRECATION_MESSAGE));

        assertEquals("index", getResponse.getIndex());
        assertEquals("type", getResponse.getType());
        assertEquals("id", getResponse.getId());

        assertTrue(getResponse.isExists());
        assertFalse(getResponse.isSourceEmpty());
        assertEquals(1L, getResponse.getVersion());
        assertEquals(document, getResponse.getSourceAsString());
    }

    public void testMultiGet() throws IOException {
        {
            MultiGetRequest multiGetRequest = new MultiGetRequest();
            multiGetRequest.add("index", "id1");
            multiGetRequest.add("index", "id2");
            MultiGetResponse response = execute(multiGetRequest, highLevelClient()::mget, highLevelClient()::mgetAsync);
            assertEquals(2, response.getResponses().length);

            assertTrue(response.getResponses()[0].isFailed());
            assertNull(response.getResponses()[0].getResponse());
            assertEquals("id1", response.getResponses()[0].getFailure().getId());
            assertNull(response.getResponses()[0].getFailure().getType());
            assertEquals("index", response.getResponses()[0].getFailure().getIndex());
            assertEquals("Elasticsearch exception [type=index_not_found_exception, reason=no such index [index]]",
                    response.getResponses()[0].getFailure().getFailure().getMessage());

            assertTrue(response.getResponses()[1].isFailed());
            assertNull(response.getResponses()[1].getResponse());
            assertEquals("id2", response.getResponses()[1].getId());
            assertNull(response.getResponses()[1].getType());
            assertEquals("index", response.getResponses()[1].getIndex());
            assertEquals("Elasticsearch exception [type=index_not_found_exception, reason=no such index [index]]",
                    response.getResponses()[1].getFailure().getFailure().getMessage());
        }
        BulkRequest bulk = new BulkRequest();
        bulk.setRefreshPolicy(RefreshPolicy.IMMEDIATE);
        IndexRequest index = new IndexRequest("index").id("id1");
        index.source("{\"field\":\"value1\"}", XContentType.JSON);
        bulk.add(index);
        index = new IndexRequest("index").id("id2");
        index.source("{\"field\":\"value2\"}", XContentType.JSON);
        bulk.add(index);
        highLevelClient().bulk(bulk, RequestOptions.DEFAULT);
        {
            MultiGetRequest multiGetRequest = new MultiGetRequest();
            multiGetRequest.add("index", "id1");
            multiGetRequest.add("index", "id2");
            MultiGetResponse response = execute(multiGetRequest, highLevelClient()::mget, highLevelClient()::mgetAsync);
            assertEquals(2, response.getResponses().length);

            assertFalse(response.getResponses()[0].isFailed());
            assertNull(response.getResponses()[0].getFailure());
            assertEquals("id1", response.getResponses()[0].getId());
            assertEquals("_doc", response.getResponses()[0].getType());
            assertEquals("index", response.getResponses()[0].getIndex());
            assertEquals(Collections.singletonMap("field", "value1"), response.getResponses()[0].getResponse().getSource());

            assertFalse(response.getResponses()[1].isFailed());
            assertNull(response.getResponses()[1].getFailure());
            assertEquals("id2", response.getResponses()[1].getId());
            assertEquals("_doc", response.getResponses()[1].getType());
            assertEquals("index", response.getResponses()[1].getIndex());
            assertEquals(Collections.singletonMap("field", "value2"), response.getResponses()[1].getResponse().getSource());
        }
    }

    public void testMultiGetWithTypes() throws IOException {
        BulkRequest bulk = new BulkRequest();
        bulk.setRefreshPolicy(RefreshPolicy.IMMEDIATE);
        bulk.add(new IndexRequest("index", "type", "id1")
            .source("{\"field\":\"value1\"}", XContentType.JSON));
        bulk.add(new IndexRequest("index", "type", "id2")
            .source("{\"field\":\"value2\"}", XContentType.JSON));

        highLevelClient().bulk(bulk, expectWarnings(RestBulkAction.TYPES_DEPRECATION_MESSAGE));
        MultiGetRequest multiGetRequest = new MultiGetRequest();
        multiGetRequest.add("index", "id1");
        multiGetRequest.add("index", "type", "id2");

        MultiGetResponse response = execute(multiGetRequest,
            highLevelClient()::mget,
            highLevelClient()::mgetAsync,
            expectWarnings(RestMultiGetAction.TYPES_DEPRECATION_MESSAGE));
        assertEquals(2, response.getResponses().length);

        GetResponse firstResponse = response.getResponses()[0].getResponse();
        assertEquals("index", firstResponse.getIndex());
        assertEquals("type", firstResponse.getType());
        assertEquals("id1", firstResponse.getId());

        GetResponse secondResponse = response.getResponses()[1].getResponse();
        assertEquals("index", secondResponse.getIndex());
        assertEquals("type", secondResponse.getType());
        assertEquals("id2", secondResponse.getId());
    }

    public void testIndex() throws IOException {
        final XContentType xContentType = randomFrom(XContentType.values());
        {
            IndexRequest indexRequest = new IndexRequest("index");
            indexRequest.source(XContentBuilder.builder(xContentType.xContent()).startObject().field("test", "test").endObject());

            IndexResponse indexResponse = execute(indexRequest, highLevelClient()::index, highLevelClient()::indexAsync);
            assertEquals(RestStatus.CREATED, indexResponse.status());
            assertEquals(DocWriteResponse.Result.CREATED, indexResponse.getResult());
            assertEquals("index", indexResponse.getIndex());
            assertEquals("_doc", indexResponse.getType());
            assertTrue(Strings.hasLength(indexResponse.getId()));
            assertEquals(1L, indexResponse.getVersion());
            assertNotNull(indexResponse.getShardId());
            assertEquals(-1, indexResponse.getShardId().getId());
            assertEquals("index", indexResponse.getShardId().getIndexName());
            assertEquals("index", indexResponse.getShardId().getIndex().getName());
            assertEquals("_na_", indexResponse.getShardId().getIndex().getUUID());
            assertNotNull(indexResponse.getShardInfo());
            assertEquals(0, indexResponse.getShardInfo().getFailed());
            assertTrue(indexResponse.getShardInfo().getSuccessful() > 0);
            assertTrue(indexResponse.getShardInfo().getTotal() > 0);
        }
        {
            IndexRequest indexRequest = new IndexRequest("index").id("id");
            indexRequest.source(XContentBuilder.builder(xContentType.xContent()).startObject().field("version", 1).endObject());

            IndexResponse indexResponse = execute(indexRequest, highLevelClient()::index, highLevelClient()::indexAsync);
            assertEquals(RestStatus.CREATED, indexResponse.status());
            assertEquals("index", indexResponse.getIndex());
            assertEquals("_doc", indexResponse.getType());
            assertEquals("id", indexResponse.getId());
            assertEquals(1L, indexResponse.getVersion());

            indexRequest = new IndexRequest("index").id("id");
            indexRequest.source(XContentBuilder.builder(xContentType.xContent()).startObject().field("version", 2).endObject());

            indexResponse = execute(indexRequest, highLevelClient()::index, highLevelClient()::indexAsync);
            assertEquals(RestStatus.OK, indexResponse.status());
            assertEquals("index", indexResponse.getIndex());
            assertEquals("_doc", indexResponse.getType());
            assertEquals("id", indexResponse.getId());
            assertEquals(2L, indexResponse.getVersion());

            ElasticsearchStatusException exception = expectThrows(ElasticsearchStatusException.class, () -> {
                IndexRequest wrongRequest = new IndexRequest("index").id("id");
                wrongRequest.source(XContentBuilder.builder(xContentType.xContent()).startObject().field("field", "test").endObject());
                wrongRequest.setIfSeqNo(1L).setIfPrimaryTerm(5L);

                execute(wrongRequest, highLevelClient()::index, highLevelClient()::indexAsync);
            });
            assertEquals(RestStatus.CONFLICT, exception.status());
            assertEquals("Elasticsearch exception [type=version_conflict_engine_exception, reason=[id]: " +
                         "version conflict, required seqNo [1], primary term [5]. current document has seqNo [2] and primary term [1]]",
                exception.getMessage());
            assertEquals("index", exception.getMetadata("es.index").get(0));
        }
        {
            ElasticsearchStatusException exception = expectThrows(ElasticsearchStatusException.class, () -> {
                IndexRequest indexRequest = new IndexRequest("index").id("missing_pipeline");
                indexRequest.source(XContentBuilder.builder(xContentType.xContent()).startObject().field("field", "test").endObject());
                indexRequest.setPipeline("missing");

                execute(indexRequest, highLevelClient()::index, highLevelClient()::indexAsync);
            });

            assertEquals(RestStatus.BAD_REQUEST, exception.status());
            assertEquals("Elasticsearch exception [type=illegal_argument_exception, " +
                         "reason=pipeline with id [missing] does not exist]", exception.getMessage());
        }
        {
            IndexRequest indexRequest = new IndexRequest("index").id("external_version_type");
            indexRequest.source(XContentBuilder.builder(xContentType.xContent()).startObject().field("field", "test").endObject());
            indexRequest.version(12L);
            indexRequest.versionType(VersionType.EXTERNAL);

            IndexResponse indexResponse = execute(indexRequest, highLevelClient()::index, highLevelClient()::indexAsync);
            assertEquals(RestStatus.CREATED, indexResponse.status());
            assertEquals("index", indexResponse.getIndex());
            assertEquals("_doc", indexResponse.getType());
            assertEquals("external_version_type", indexResponse.getId());
            assertEquals(12L, indexResponse.getVersion());
        }
        {
            final IndexRequest indexRequest = new IndexRequest("index").id("with_create_op_type");
            indexRequest.source(XContentBuilder.builder(xContentType.xContent()).startObject().field("field", "test").endObject());
            indexRequest.opType(DocWriteRequest.OpType.CREATE);

            IndexResponse indexResponse = execute(indexRequest, highLevelClient()::index, highLevelClient()::indexAsync);
            assertEquals(RestStatus.CREATED, indexResponse.status());
            assertEquals("index", indexResponse.getIndex());
            assertEquals("_doc", indexResponse.getType());
            assertEquals("with_create_op_type", indexResponse.getId());

            ElasticsearchStatusException exception = expectThrows(ElasticsearchStatusException.class, () -> {
                execute(indexRequest, highLevelClient()::index, highLevelClient()::indexAsync);
            });

            assertEquals(RestStatus.CONFLICT, exception.status());
            assertEquals("Elasticsearch exception [type=version_conflict_engine_exception, reason=[with_create_op_type]: " +
                         "version conflict, document already exists (current version [1])]", exception.getMessage());
        }
    }

    public void testIndexWithTypes() throws IOException {
        final XContentType xContentType = randomFrom(XContentType.values());
        IndexRequest indexRequest = new IndexRequest("index", "some_type", "some_id");
        indexRequest.source(XContentBuilder.builder(xContentType.xContent()).startObject().field("test", "test").endObject());
        IndexResponse indexResponse = execute(
            indexRequest,
            highLevelClient()::index,
            highLevelClient()::indexAsync,
            expectWarnings(RestIndexAction.TYPES_DEPRECATION_MESSAGE)
        );
        assertEquals(RestStatus.CREATED, indexResponse.status());
        assertEquals("index", indexResponse.getIndex());
        assertEquals("some_type", indexResponse.getType());
        assertEquals("some_id",indexResponse.getId());
    }

    public void testUpdate() throws IOException {
        {
            UpdateRequest updateRequest = new UpdateRequest("index", "does_not_exist");
            updateRequest.doc(singletonMap("field", "value"), randomFrom(XContentType.values()));

            ElasticsearchStatusException exception = expectThrows(ElasticsearchStatusException.class, () ->
                    execute(updateRequest, highLevelClient()::update, highLevelClient()::updateAsync));
            assertEquals(RestStatus.NOT_FOUND, exception.status());
            assertEquals("Elasticsearch exception [type=document_missing_exception, reason=[_doc][does_not_exist]: document missing]",
                    exception.getMessage());
        }
        {
            IndexRequest indexRequest = new IndexRequest("index").id( "id");
            indexRequest.source(singletonMap("field", "value"));
            IndexResponse indexResponse = highLevelClient().index(indexRequest, RequestOptions.DEFAULT);
            assertEquals(RestStatus.CREATED, indexResponse.status());


            long lastUpdateSeqNo;
            long lastUpdatePrimaryTerm;
            {
                UpdateRequest updateRequest = new UpdateRequest("index", "id");
                updateRequest.doc(singletonMap("field", "updated"), randomFrom(XContentType.values()));
                final UpdateResponse updateResponse = execute(updateRequest, highLevelClient()::update, highLevelClient()::updateAsync);
                assertEquals(RestStatus.OK, updateResponse.status());
                assertEquals(indexResponse.getVersion() + 1, updateResponse.getVersion());
                lastUpdateSeqNo = updateResponse.getSeqNo();
                lastUpdatePrimaryTerm = updateResponse.getPrimaryTerm();
                assertThat(lastUpdateSeqNo, greaterThanOrEqualTo(0L));
                assertThat(lastUpdatePrimaryTerm, greaterThanOrEqualTo(1L));
            }

            {
                final UpdateRequest updateRequest = new UpdateRequest("index", "id");
                updateRequest.doc(singletonMap("field", "with_seq_no_conflict"), randomFrom(XContentType.values()));
                if (randomBoolean()) {
                    updateRequest.setIfSeqNo(lastUpdateSeqNo + 1);
                    updateRequest.setIfPrimaryTerm(lastUpdatePrimaryTerm);
                } else {
                    updateRequest.setIfSeqNo(lastUpdateSeqNo + (randomBoolean() ? 0 : 1));
                    updateRequest.setIfPrimaryTerm(lastUpdatePrimaryTerm + 1);
                }
                ElasticsearchStatusException exception = expectThrows(ElasticsearchStatusException.class, () ->
                    execute(updateRequest, highLevelClient()::update, highLevelClient()::updateAsync));
                assertEquals(exception.toString(),RestStatus.CONFLICT, exception.status());
                assertThat(exception.getMessage(), containsString("Elasticsearch exception [type=version_conflict_engine_exception"));
            }
            {
                final UpdateRequest updateRequest = new UpdateRequest("index", "id");
                updateRequest.doc(singletonMap("field", "with_seq_no"), randomFrom(XContentType.values()));
                updateRequest.setIfSeqNo(lastUpdateSeqNo);
                updateRequest.setIfPrimaryTerm(lastUpdatePrimaryTerm);
                final UpdateResponse updateResponse = execute(updateRequest, highLevelClient()::update, highLevelClient()::updateAsync);
                assertEquals(RestStatus.OK, updateResponse.status());
                assertEquals(lastUpdateSeqNo + 1, updateResponse.getSeqNo());
                assertEquals(lastUpdatePrimaryTerm, updateResponse.getPrimaryTerm());
            }
        }
        {
            IndexRequest indexRequest = new IndexRequest("index").id("with_script");
            indexRequest.source(singletonMap("counter", 12));
            IndexResponse indexResponse = highLevelClient().index(indexRequest, RequestOptions.DEFAULT);
            assertEquals(RestStatus.CREATED, indexResponse.status());

            UpdateRequest updateRequest = new UpdateRequest("index", "with_script");
            Script script = new Script(ScriptType.INLINE, "painless", "ctx._source.counter += params.count", singletonMap("count", 8));
            updateRequest.script(script);
            updateRequest.fetchSource(true);

            UpdateResponse updateResponse = execute(updateRequest, highLevelClient()::update, highLevelClient()::updateAsync);
            assertEquals(RestStatus.OK, updateResponse.status());
            assertEquals(DocWriteResponse.Result.UPDATED, updateResponse.getResult());
            assertEquals(2L, updateResponse.getVersion());
            assertEquals(20, updateResponse.getGetResult().sourceAsMap().get("counter"));

        }
        {
            IndexRequest indexRequest = new IndexRequest("index").id("with_doc");
            indexRequest.source("field_1", "one", "field_3", "three");
            indexRequest.version(12L);
            indexRequest.versionType(VersionType.EXTERNAL);
            IndexResponse indexResponse = highLevelClient().index(indexRequest, RequestOptions.DEFAULT);
            assertEquals(RestStatus.CREATED, indexResponse.status());
            assertEquals(12L, indexResponse.getVersion());

            UpdateRequest updateRequest = new UpdateRequest("index", "with_doc");
            updateRequest.doc(singletonMap("field_2", "two"), randomFrom(XContentType.values()));
            updateRequest.fetchSource("field_*", "field_3");

            UpdateResponse updateResponse = execute(updateRequest, highLevelClient()::update, highLevelClient()::updateAsync);
            assertEquals(RestStatus.OK, updateResponse.status());
            assertEquals(DocWriteResponse.Result.UPDATED, updateResponse.getResult());
            assertEquals(13L, updateResponse.getVersion());
            GetResult getResult = updateResponse.getGetResult();
            assertEquals(13L, updateResponse.getVersion());
            Map<String, Object> sourceAsMap = getResult.sourceAsMap();
            assertEquals("one", sourceAsMap.get("field_1"));
            assertEquals("two", sourceAsMap.get("field_2"));
            assertFalse(sourceAsMap.containsKey("field_3"));
        }
        {
            IndexRequest indexRequest = new IndexRequest("index").id("noop");
            indexRequest.source("field", "value");
            IndexResponse indexResponse = highLevelClient().index(indexRequest, RequestOptions.DEFAULT);
            assertEquals(RestStatus.CREATED, indexResponse.status());
            assertEquals(1L, indexResponse.getVersion());

            UpdateRequest updateRequest = new UpdateRequest("index", "noop");
            updateRequest.doc(singletonMap("field", "value"), randomFrom(XContentType.values()));

            UpdateResponse updateResponse = execute(updateRequest, highLevelClient()::update, highLevelClient()::updateAsync);
            assertEquals(RestStatus.OK, updateResponse.status());
            assertEquals(DocWriteResponse.Result.NOOP, updateResponse.getResult());
            assertEquals(1L, updateResponse.getVersion());

            updateRequest.detectNoop(false);

            updateResponse = execute(updateRequest, highLevelClient()::update, highLevelClient()::updateAsync);
            assertEquals(RestStatus.OK, updateResponse.status());
            assertEquals(DocWriteResponse.Result.UPDATED, updateResponse.getResult());
            assertEquals(2L, updateResponse.getVersion());
        }
        {
            UpdateRequest updateRequest = new UpdateRequest("index", "with_upsert");
            updateRequest.upsert(singletonMap("doc_status", "created"));
            updateRequest.doc(singletonMap("doc_status", "updated"));
            updateRequest.fetchSource(true);

            UpdateResponse updateResponse = execute(updateRequest, highLevelClient()::update, highLevelClient()::updateAsync);
            assertEquals(RestStatus.CREATED, updateResponse.status());
            assertEquals("index", updateResponse.getIndex());
            assertEquals("_doc", updateResponse.getType());
            assertEquals("with_upsert", updateResponse.getId());
            GetResult getResult = updateResponse.getGetResult();
            assertEquals(1L, updateResponse.getVersion());
            assertEquals("created", getResult.sourceAsMap().get("doc_status"));
        }
        {
            UpdateRequest updateRequest = new UpdateRequest("index", "with_doc_as_upsert");
            updateRequest.doc(singletonMap("field", "initialized"));
            updateRequest.fetchSource(true);
            updateRequest.docAsUpsert(true);

            UpdateResponse updateResponse = execute(updateRequest, highLevelClient()::update, highLevelClient()::updateAsync);
            assertEquals(RestStatus.CREATED, updateResponse.status());
            assertEquals("index", updateResponse.getIndex());
            assertEquals("_doc", updateResponse.getType());
            assertEquals("with_doc_as_upsert", updateResponse.getId());
            GetResult getResult = updateResponse.getGetResult();
            assertEquals(1L, updateResponse.getVersion());
            assertEquals("initialized", getResult.sourceAsMap().get("field"));
        }
        {
            UpdateRequest updateRequest = new UpdateRequest("index", "with_scripted_upsert");
            updateRequest.fetchSource(true);
            updateRequest.script(new Script(ScriptType.INLINE, "painless", "ctx._source.level = params.test", singletonMap("test", "C")));
            updateRequest.scriptedUpsert(true);
            updateRequest.upsert(singletonMap("level", "A"));

            UpdateResponse updateResponse = execute(updateRequest, highLevelClient()::update, highLevelClient()::updateAsync);
            assertEquals(RestStatus.CREATED, updateResponse.status());
            assertEquals("index", updateResponse.getIndex());
            assertEquals("_doc", updateResponse.getType());
            assertEquals("with_scripted_upsert", updateResponse.getId());

            GetResult getResult = updateResponse.getGetResult();
            assertEquals(1L, updateResponse.getVersion());
            assertEquals("C", getResult.sourceAsMap().get("level"));
        }
        {
            IllegalStateException exception = expectThrows(IllegalStateException.class, () -> {
                UpdateRequest updateRequest = new UpdateRequest("index", "id");
                updateRequest.doc(new IndexRequest().source(Collections.singletonMap("field", "doc"), XContentType.JSON));
                updateRequest.upsert(new IndexRequest().source(Collections.singletonMap("field", "upsert"), XContentType.YAML));
                execute(updateRequest, highLevelClient()::update, highLevelClient()::updateAsync);
            });
            assertEquals("Update request cannot have different content types for doc [JSON] and upsert [YAML] documents",
                    exception.getMessage());
        }
    }

    public void testUpdateWithTypes() throws IOException {
        IndexRequest indexRequest = new IndexRequest("index", "type", "id");
        indexRequest.source(singletonMap("field", "value"));
        IndexResponse indexResponse = execute(indexRequest,
            highLevelClient()::index,
            highLevelClient()::indexAsync,
            expectWarnings(RestIndexAction.TYPES_DEPRECATION_MESSAGE)
        );

        UpdateRequest updateRequest = new UpdateRequest("index", "type", "id");
        updateRequest.doc(singletonMap("field", "updated"), randomFrom(XContentType.values()));
        UpdateResponse updateResponse = execute(updateRequest,
            highLevelClient()::update,
            highLevelClient()::updateAsync,
            expectWarnings(RestUpdateAction.TYPES_DEPRECATION_MESSAGE));

        assertEquals(RestStatus.OK, updateResponse.status());
        assertEquals(indexResponse.getVersion() + 1, updateResponse.getVersion());
    }

    public void testBulk() throws IOException {
        int nbItems = randomIntBetween(10, 100);
        boolean[] errors = new boolean[nbItems];

        XContentType xContentType = randomFrom(XContentType.JSON, XContentType.SMILE);

        BulkRequest bulkRequest = new BulkRequest();
        for (int i = 0; i < nbItems; i++) {
            String id = String.valueOf(i);
            boolean erroneous = randomBoolean();
            errors[i] = erroneous;

            DocWriteRequest.OpType opType = randomFrom(DocWriteRequest.OpType.values());
            if (opType == DocWriteRequest.OpType.DELETE) {
                if (erroneous == false) {
                    assertEquals(RestStatus.CREATED,
                            highLevelClient().index(
                                    new IndexRequest("index").id(id).source("field", -1), RequestOptions.DEFAULT).status());
                }
                DeleteRequest deleteRequest = new DeleteRequest("index", id);
                bulkRequest.add(deleteRequest);

            } else {
                BytesReference source = BytesReference.bytes(XContentBuilder.builder(xContentType.xContent())
                        .startObject().field("id", i).endObject());
                if (opType == DocWriteRequest.OpType.INDEX) {
                    IndexRequest indexRequest = new IndexRequest("index").id(id).source(source, xContentType);
                    if (erroneous) {
                        indexRequest.setIfSeqNo(12L);
                        indexRequest.setIfPrimaryTerm(12L);
                    }
                    bulkRequest.add(indexRequest);

                } else if (opType == DocWriteRequest.OpType.CREATE) {
                    IndexRequest createRequest = new IndexRequest("index").id(id).source(source, xContentType).create(true);
                    if (erroneous) {
                        assertEquals(RestStatus.CREATED, highLevelClient().index(createRequest, RequestOptions.DEFAULT).status());
                    }
                    bulkRequest.add(createRequest);

                } else if (opType == DocWriteRequest.OpType.UPDATE) {
                    UpdateRequest updateRequest = new UpdateRequest("index", id)
                            .doc(new IndexRequest().source(source, xContentType));
                    if (erroneous == false) {
                        assertEquals(RestStatus.CREATED,
                                highLevelClient().index(
                                        new IndexRequest("index").id(id).source("field", -1), RequestOptions.DEFAULT).status());
                    }
                    bulkRequest.add(updateRequest);
                }
            }
        }

        BulkResponse bulkResponse = execute(bulkRequest, highLevelClient()::bulk, highLevelClient()::bulkAsync, RequestOptions.DEFAULT);
        assertEquals(RestStatus.OK, bulkResponse.status());
        assertTrue(bulkResponse.getTook().getMillis() > 0);
        assertEquals(nbItems, bulkResponse.getItems().length);

        validateBulkResponses(nbItems, errors, bulkResponse, bulkRequest);
    }

    private TaskId findTaskToRethrottle(String actionName) throws IOException {
        long start = System.nanoTime();
        ListTasksRequest request = new ListTasksRequest();
        request.setActions(actionName);
        request.setDetailed(true);
        do {
            ListTasksResponse list = highLevelClient().tasks().list(request, RequestOptions.DEFAULT);
            list.rethrowFailures("Finding tasks to rethrottle");
            assertThat("tasks are left over from the last execution of this test",
                list.getTaskGroups(), hasSize(lessThan(2)));
            if (0 == list.getTaskGroups().size()) {
                // The parent task hasn't started yet
                continue;
            }
            TaskGroup taskGroup = list.getTaskGroups().get(0);
            assertThat(taskGroup.getChildTasks(), empty());
            return taskGroup.getTaskInfo().getTaskId();
        } while (System.nanoTime() - start < TimeUnit.SECONDS.toNanos(10));
        throw new AssertionError("Couldn't find tasks to rethrottle. Here are the running tasks " +
                highLevelClient().tasks().list(request, RequestOptions.DEFAULT));
    }

    public void testUpdateByQuery() throws Exception {
        final String sourceIndex = "source1";
        {
            // Prepare
            Settings settings = Settings.builder()
                .put("number_of_shards", 1)
                .put("number_of_replicas", 0)
                .build();
            createIndex(sourceIndex, settings);
            assertEquals(
                RestStatus.OK,
                highLevelClient().bulk(
                    new BulkRequest()
                        .add(new IndexRequest(sourceIndex).id("1")
                            .source(Collections.singletonMap("foo", 1), XContentType.JSON))
                        .add(new IndexRequest(sourceIndex).id("2")
                            .source(Collections.singletonMap("foo", 2), XContentType.JSON))
                        .setRefreshPolicy(RefreshPolicy.IMMEDIATE),
                    RequestOptions.DEFAULT
                ).status()
            );
        }
        {
            // test1: create one doc in dest
            UpdateByQueryRequest updateByQueryRequest = new UpdateByQueryRequest();
            updateByQueryRequest.indices(sourceIndex);
            updateByQueryRequest.setQuery(new IdsQueryBuilder().addIds("1"));
            updateByQueryRequest.setRefresh(true);
            BulkByScrollResponse bulkResponse =
                execute(updateByQueryRequest, highLevelClient()::updateByQuery, highLevelClient()::updateByQueryAsync);
            assertEquals(1, bulkResponse.getTotal());
            assertEquals(1, bulkResponse.getUpdated());
            assertEquals(0, bulkResponse.getNoops());
            assertEquals(0, bulkResponse.getVersionConflicts());
            assertEquals(1, bulkResponse.getBatches());
            assertTrue(bulkResponse.getTook().getMillis() > 0);
            assertEquals(1, bulkResponse.getBatches());
            assertEquals(0, bulkResponse.getBulkFailures().size());
            assertEquals(0, bulkResponse.getSearchFailures().size());
        }
        {
            // test2: update using script
            UpdateByQueryRequest updateByQueryRequest = new UpdateByQueryRequest();
            updateByQueryRequest.indices(sourceIndex);
            updateByQueryRequest.setScript(new Script("if (ctx._source.foo == 2) ctx._source.foo++;"));
            updateByQueryRequest.setRefresh(true);
            BulkByScrollResponse bulkResponse =
                execute(updateByQueryRequest, highLevelClient()::updateByQuery, highLevelClient()::updateByQueryAsync);
            assertEquals(2, bulkResponse.getTotal());
            assertEquals(2, bulkResponse.getUpdated());
            assertEquals(0, bulkResponse.getDeleted());
            assertEquals(0, bulkResponse.getNoops());
            assertEquals(0, bulkResponse.getVersionConflicts());
            assertEquals(1, bulkResponse.getBatches());
            assertTrue(bulkResponse.getTook().getMillis() > 0);
            assertEquals(1, bulkResponse.getBatches());
            assertEquals(0, bulkResponse.getBulkFailures().size());
            assertEquals(0, bulkResponse.getSearchFailures().size());
            assertEquals(
                3,
                (int) (highLevelClient().get(new GetRequest(sourceIndex, "2"), RequestOptions.DEFAULT)
                    .getSourceAsMap().get("foo"))
            );
        }
        {
            // test update-by-query rethrottling
            UpdateByQueryRequest updateByQueryRequest = new UpdateByQueryRequest();
            updateByQueryRequest.indices(sourceIndex);
            updateByQueryRequest.setQuery(new IdsQueryBuilder().addIds("1"));
            updateByQueryRequest.setRefresh(true);

            // this following settings are supposed to halt reindexing after first document
            updateByQueryRequest.setBatchSize(1);
            updateByQueryRequest.setRequestsPerSecond(0.00001f);
            final CountDownLatch taskFinished = new CountDownLatch(1);
            highLevelClient().updateByQueryAsync(updateByQueryRequest, RequestOptions.DEFAULT, new ActionListener<BulkByScrollResponse>() {

                @Override
                public void onResponse(BulkByScrollResponse response) {
                    taskFinished.countDown();
                }

                @Override
                public void onFailure(Exception e) {
                    fail(e.toString());
                }
            });

            TaskId taskIdToRethrottle = findTaskToRethrottle(UpdateByQueryAction.NAME);
            float requestsPerSecond = 1000f;
            ListTasksResponse response = execute(new RethrottleRequest(taskIdToRethrottle, requestsPerSecond),
                    highLevelClient()::updateByQueryRethrottle, highLevelClient()::updateByQueryRethrottleAsync);
            assertThat(response.getTasks(), hasSize(1));
            assertEquals(taskIdToRethrottle, response.getTasks().get(0).getTaskId());
            assertThat(response.getTasks().get(0).getStatus(), instanceOf(RawTaskStatus.class));
            assertEquals(Float.toString(requestsPerSecond),
                    ((RawTaskStatus) response.getTasks().get(0).getStatus()).toMap().get("requests_per_second").toString());
            taskFinished.await(2, TimeUnit.SECONDS);

            // any rethrottling after the update-by-query is done performed with the same taskId should result in a failure
            response = execute(new RethrottleRequest(taskIdToRethrottle, requestsPerSecond),
                    highLevelClient()::updateByQueryRethrottle, highLevelClient()::updateByQueryRethrottleAsync);
            assertTrue(response.getTasks().isEmpty());
            assertFalse(response.getNodeFailures().isEmpty());
            assertEquals(1, response.getNodeFailures().size());
            assertEquals("Elasticsearch exception [type=resource_not_found_exception, reason=task [" + taskIdToRethrottle + "] is missing]",
                    response.getNodeFailures().get(0).getCause().getMessage());
        }
    }

    public void testDeleteByQuery() throws Exception {
        final String sourceIndex = "source1";
        {
            // Prepare
            Settings settings = Settings.builder()
                .put("number_of_shards", 1)
                .put("number_of_replicas", 0)
                .build();
            createIndex(sourceIndex, settings);
            assertEquals(
                RestStatus.OK,
                highLevelClient().bulk(
                    new BulkRequest()
                        .add(new IndexRequest(sourceIndex).id("1")
                            .source(Collections.singletonMap("foo", 1), XContentType.JSON))
                        .add(new IndexRequest(sourceIndex).id("2")
                            .source(Collections.singletonMap("foo", 2), XContentType.JSON))
                        .add(new IndexRequest(sourceIndex).id("3")
                            .source(Collections.singletonMap("foo", 3), XContentType.JSON))
                        .setRefreshPolicy(RefreshPolicy.IMMEDIATE),
                    RequestOptions.DEFAULT
                ).status()
            );
        }
        {
            // test1: delete one doc
            DeleteByQueryRequest deleteByQueryRequest = new DeleteByQueryRequest();
            deleteByQueryRequest.indices(sourceIndex);
            deleteByQueryRequest.setQuery(new IdsQueryBuilder().addIds("1"));
            deleteByQueryRequest.setRefresh(true);
            BulkByScrollResponse bulkResponse =
                execute(deleteByQueryRequest, highLevelClient()::deleteByQuery, highLevelClient()::deleteByQueryAsync);
            assertEquals(1, bulkResponse.getTotal());
            assertEquals(1, bulkResponse.getDeleted());
            assertEquals(0, bulkResponse.getNoops());
            assertEquals(0, bulkResponse.getVersionConflicts());
            assertEquals(1, bulkResponse.getBatches());
            assertTrue(bulkResponse.getTook().getMillis() > 0);
            assertEquals(1, bulkResponse.getBatches());
            assertEquals(0, bulkResponse.getBulkFailures().size());
            assertEquals(0, bulkResponse.getSearchFailures().size());
            assertEquals(
                2,
                highLevelClient().search(new SearchRequest(sourceIndex), RequestOptions.DEFAULT).getHits().getTotalHits().value
            );
        }
        {
            // test delete-by-query rethrottling
            DeleteByQueryRequest deleteByQueryRequest = new DeleteByQueryRequest();
            deleteByQueryRequest.indices(sourceIndex);
            deleteByQueryRequest.setQuery(new IdsQueryBuilder().addIds("2", "3"));
            deleteByQueryRequest.setRefresh(true);

            // this following settings are supposed to halt reindexing after first document
            deleteByQueryRequest.setBatchSize(1);
            deleteByQueryRequest.setRequestsPerSecond(0.00001f);
            final CountDownLatch taskFinished = new CountDownLatch(1);
            highLevelClient().deleteByQueryAsync(deleteByQueryRequest, RequestOptions.DEFAULT, new ActionListener<BulkByScrollResponse>() {

                @Override
                public void onResponse(BulkByScrollResponse response) {
                    taskFinished.countDown();
                }

                @Override
                public void onFailure(Exception e) {
                    fail(e.toString());
                }
            });

            TaskId taskIdToRethrottle = findTaskToRethrottle(DeleteByQueryAction.NAME);
            float requestsPerSecond = 1000f;
            ListTasksResponse response = execute(new RethrottleRequest(taskIdToRethrottle, requestsPerSecond),
                    highLevelClient()::deleteByQueryRethrottle, highLevelClient()::deleteByQueryRethrottleAsync);
            assertThat(response.getTasks(), hasSize(1));
            assertEquals(taskIdToRethrottle, response.getTasks().get(0).getTaskId());
            assertThat(response.getTasks().get(0).getStatus(), instanceOf(RawTaskStatus.class));
            assertEquals(Float.toString(requestsPerSecond),
                    ((RawTaskStatus) response.getTasks().get(0).getStatus()).toMap().get("requests_per_second").toString());
            taskFinished.await(2, TimeUnit.SECONDS);

            // any rethrottling after the delete-by-query is done performed with the same taskId should result in a failure
            response = execute(new RethrottleRequest(taskIdToRethrottle, requestsPerSecond),
                    highLevelClient()::deleteByQueryRethrottle, highLevelClient()::deleteByQueryRethrottleAsync);
            assertTrue(response.getTasks().isEmpty());
            assertFalse(response.getNodeFailures().isEmpty());
            assertEquals(1, response.getNodeFailures().size());
            assertEquals("Elasticsearch exception [type=resource_not_found_exception, reason=task [" + taskIdToRethrottle + "] is missing]",
                    response.getNodeFailures().get(0).getCause().getMessage());
        }
    }

    public void testBulkProcessorIntegration() throws IOException {
        int nbItems = randomIntBetween(10, 100);
        boolean[] errors = new boolean[nbItems];

        XContentType xContentType = randomFrom(XContentType.JSON, XContentType.SMILE);

        AtomicReference<BulkResponse> responseRef = new AtomicReference<>();
        AtomicReference<BulkRequest> requestRef = new AtomicReference<>();
        AtomicReference<Throwable> error = new AtomicReference<>();

        BulkProcessor.Listener listener = new BulkProcessor.Listener() {
            @Override
            public void beforeBulk(long executionId, BulkRequest request) {

            }

            @Override
            public void afterBulk(long executionId, BulkRequest request, BulkResponse response) {
                responseRef.set(response);
                requestRef.set(request);
            }

            @Override
            public void afterBulk(long executionId, BulkRequest request, Throwable failure) {
                error.set(failure);
            }
        };

        try (BulkProcessor processor = BulkProcessor.builder(
                (request, bulkListener) -> highLevelClient().bulkAsync(request,
                        RequestOptions.DEFAULT, bulkListener), listener)
                .setConcurrentRequests(0)
                .setBulkSize(new ByteSizeValue(5, ByteSizeUnit.GB))
                .setBulkActions(nbItems + 1)
                .build()) {
            for (int i = 0; i < nbItems; i++) {
                String id = String.valueOf(i);
                boolean erroneous = randomBoolean();
                errors[i] = erroneous;

                DocWriteRequest.OpType opType = randomFrom(DocWriteRequest.OpType.values());
                if (opType == DocWriteRequest.OpType.DELETE) {
                    if (erroneous == false) {
                        assertEquals(RestStatus.CREATED,
                                highLevelClient().index(
                                        new IndexRequest("index").id(id).source("field", -1), RequestOptions.DEFAULT).status());
                    }
                    DeleteRequest deleteRequest = new DeleteRequest("index", id);
                    processor.add(deleteRequest);

                } else {
                    if (opType == DocWriteRequest.OpType.INDEX) {
                        IndexRequest indexRequest = new IndexRequest("index").id(id).source(xContentType, "id", i);
                        if (erroneous) {
                            indexRequest.setIfSeqNo(12L);
                            indexRequest.setIfPrimaryTerm(12L);
                        }
                        processor.add(indexRequest);

                    } else if (opType == DocWriteRequest.OpType.CREATE) {
                        IndexRequest createRequest = new IndexRequest("index").id(id).source(xContentType, "id", i).create(true);
                        if (erroneous) {
                            assertEquals(RestStatus.CREATED, highLevelClient().index(createRequest, RequestOptions.DEFAULT).status());
                        }
                        processor.add(createRequest);

                    } else if (opType == DocWriteRequest.OpType.UPDATE) {
                        UpdateRequest updateRequest = new UpdateRequest("index", id)
                                .doc(new IndexRequest().source(xContentType, "id", i));
                        if (erroneous == false) {
                            assertEquals(RestStatus.CREATED,
                                    highLevelClient().index(
                                            new IndexRequest("index").id(id).source("field", -1), RequestOptions.DEFAULT).status());
                        }
                        processor.add(updateRequest);
                    }
                }
            }
            assertNull(responseRef.get());
            assertNull(requestRef.get());
        }


        BulkResponse bulkResponse = responseRef.get();
        BulkRequest bulkRequest = requestRef.get();

        assertEquals(RestStatus.OK, bulkResponse.status());
        assertTrue(bulkResponse.getTook().getMillis() > 0);
        assertEquals(nbItems, bulkResponse.getItems().length);
        assertNull(error.get());

        validateBulkResponses(nbItems, errors, bulkResponse, bulkRequest);
    }

    private void validateBulkResponses(int nbItems, boolean[] errors, BulkResponse bulkResponse, BulkRequest bulkRequest) {
        for (int i = 0; i < nbItems; i++) {
            BulkItemResponse bulkItemResponse = bulkResponse.getItems()[i];

            assertEquals(i, bulkItemResponse.getItemId());
            assertEquals("index", bulkItemResponse.getIndex());
            assertEquals("_doc", bulkItemResponse.getType());
            assertEquals(String.valueOf(i), bulkItemResponse.getId());

            DocWriteRequest.OpType requestOpType = bulkRequest.requests().get(i).opType();
            if (requestOpType == DocWriteRequest.OpType.INDEX || requestOpType == DocWriteRequest.OpType.CREATE) {
                assertEquals(errors[i], bulkItemResponse.isFailed());
                assertEquals(errors[i] ? RestStatus.CONFLICT : RestStatus.CREATED, bulkItemResponse.status());
            } else if (requestOpType == DocWriteRequest.OpType.UPDATE) {
                assertEquals(errors[i], bulkItemResponse.isFailed());
                assertEquals(errors[i] ? RestStatus.NOT_FOUND : RestStatus.OK, bulkItemResponse.status());
            } else if (requestOpType == DocWriteRequest.OpType.DELETE) {
                assertFalse(bulkItemResponse.isFailed());
                assertEquals(errors[i] ? RestStatus.NOT_FOUND : RestStatus.OK, bulkItemResponse.status());
            }
        }
    }

    public void testUrlEncode() throws IOException {
        String indexPattern = "<logstash-{now/M}>";
        String expectedIndex = "logstash-" +
                DateTimeFormat.forPattern("YYYY.MM.dd").print(new DateTime(DateTimeZone.UTC).monthOfYear().roundFloorCopy());
        {
            IndexRequest indexRequest = new IndexRequest(indexPattern).id("id#1");
            indexRequest.source("field", "value");
            IndexResponse indexResponse = highLevelClient().index(indexRequest, RequestOptions.DEFAULT);
            assertEquals(expectedIndex, indexResponse.getIndex());
            assertEquals("_doc", indexResponse.getType());
            assertEquals("id#1", indexResponse.getId());
        }
        {
            GetRequest getRequest = new GetRequest(indexPattern, "id#1");
            GetResponse getResponse = highLevelClient().get(getRequest, RequestOptions.DEFAULT);
            assertTrue(getResponse.isExists());
            assertEquals(expectedIndex, getResponse.getIndex());
            assertEquals("_doc", getResponse.getType());
            assertEquals("id#1", getResponse.getId());
        }

        String docId = "this/is/the/id/??????";
        {
            IndexRequest indexRequest = new IndexRequest("index").id(docId);
            indexRequest.source("field", "value");
            IndexResponse indexResponse = highLevelClient().index(indexRequest, RequestOptions.DEFAULT);
            assertEquals("index", indexResponse.getIndex());
            assertEquals("_doc", indexResponse.getType());
            assertEquals(docId, indexResponse.getId());
        }
        {
            GetRequest getRequest = new GetRequest("index", docId);
            GetResponse getResponse = highLevelClient().get(getRequest, RequestOptions.DEFAULT);
            assertTrue(getResponse.isExists());
            assertEquals("index", getResponse.getIndex());
            assertEquals("_doc", getResponse.getType());
            assertEquals(docId, getResponse.getId());
        }

        assertTrue(highLevelClient().indices().exists(new GetIndexRequest(indexPattern, "index"), RequestOptions.DEFAULT));
    }

    public void testParamsEncode() throws IOException {
        //parameters are encoded by the low-level client but let's test that everything works the same when we use the high-level one
        String routing = "routing/??????value#1?";
        {
            IndexRequest indexRequest = new IndexRequest("index").id("id");
            indexRequest.source("field", "value");
            indexRequest.routing(routing);
            IndexResponse indexResponse = highLevelClient().index(indexRequest, RequestOptions.DEFAULT);
            assertEquals("index", indexResponse.getIndex());
            assertEquals("_doc", indexResponse.getType());
            assertEquals("id", indexResponse.getId());
        }
        {
            GetRequest getRequest = new GetRequest("index", "id").routing(routing);
            GetResponse getResponse = highLevelClient().get(getRequest, RequestOptions.DEFAULT);
            assertTrue(getResponse.isExists());
            assertEquals("index", getResponse.getIndex());
            assertEquals("_doc", getResponse.getType());
            assertEquals("id", getResponse.getId());
            assertEquals(routing, getResponse.getField("_routing").getValue());
        }
    }

    // Not entirely sure if _termvectors belongs to CRUD, and in the absence of a better place, will have it here
    public void testTermvectors() throws IOException {
        final String sourceIndex = "index1";
        {
            // prepare : index docs
            Settings settings = Settings.builder()
                .put("number_of_shards", 1)
                .put("number_of_replicas", 0)
                .build();
            String mappings = "\"properties\":{\"field\":{\"type\":\"text\"}}";
            createIndex(sourceIndex, settings, mappings);
            assertEquals(
                RestStatus.OK,
                highLevelClient().bulk(
                    new BulkRequest()
                        .add(new IndexRequest(sourceIndex).id("1")
                            .source(Collections.singletonMap("field", "value1"), XContentType.JSON))
                        .add(new IndexRequest(sourceIndex).id("2")
                            .source(Collections.singletonMap("field", "value2"), XContentType.JSON))
                        .setRefreshPolicy(RefreshPolicy.IMMEDIATE),
                    RequestOptions.DEFAULT
                ).status()
            );
        }
        {
            // test _termvectors on real documents
            TermVectorsRequest tvRequest = new TermVectorsRequest(sourceIndex, "1");
            tvRequest.setFields("field");
            TermVectorsResponse tvResponse = execute(tvRequest, highLevelClient()::termvectors, highLevelClient()::termvectorsAsync);

            TermVectorsResponse.TermVector.Token expectedToken = new TermVectorsResponse.TermVector.Token(0, 6, 0, null);
            TermVectorsResponse.TermVector.Term expectedTerm = new TermVectorsResponse.TermVector.Term(
                "value1", 1, null, null, null, Collections.singletonList(expectedToken));
            TermVectorsResponse.TermVector.FieldStatistics expectedFieldStats =
                new TermVectorsResponse.TermVector.FieldStatistics(2, 2, 2);
            TermVectorsResponse.TermVector expectedTV =
                new TermVectorsResponse.TermVector("field", expectedFieldStats, Collections.singletonList(expectedTerm));
            List<TermVectorsResponse.TermVector> expectedTVlist = Collections.singletonList(expectedTV);

            assertThat(tvResponse.getIndex(), equalTo(sourceIndex));
            assertThat(Integer.valueOf(tvResponse.getId()), equalTo(1));
            assertTrue(tvResponse.getFound());
            assertEquals(expectedTVlist, tvResponse.getTermVectorsList());
        }
        {
            // test _termvectors on artificial documents
            XContentBuilder docBuilder = XContentFactory.jsonBuilder();
            docBuilder.startObject().field("field", "valuex").endObject();

            TermVectorsRequest tvRequest = new TermVectorsRequest(sourceIndex, docBuilder);
            TermVectorsResponse tvResponse = execute(tvRequest, highLevelClient()::termvectors, highLevelClient()::termvectorsAsync);

            TermVectorsResponse.TermVector.Token expectedToken = new TermVectorsResponse.TermVector.Token(0, 6, 0, null);
            TermVectorsResponse.TermVector.Term expectedTerm = new TermVectorsResponse.TermVector.Term(
                "valuex", 1, null, null, null, Collections.singletonList(expectedToken));
            TermVectorsResponse.TermVector.FieldStatistics expectedFieldStats =
                new TermVectorsResponse.TermVector.FieldStatistics(2, 2, 2);
            TermVectorsResponse.TermVector expectedTV =
                new TermVectorsResponse.TermVector("field", expectedFieldStats, Collections.singletonList(expectedTerm));
            List<TermVectorsResponse.TermVector> expectedTVlist = Collections.singletonList(expectedTV);

            assertThat(tvResponse.getIndex(), equalTo(sourceIndex));
            assertTrue(tvResponse.getFound());
            assertEquals(expectedTVlist, tvResponse.getTermVectorsList());
        }
    }

    // Not entirely sure if _termvectors belongs to CRUD, and in the absence of a better place, will have it here
    public void testTermvectorsWithNonExistentIndex() {
        TermVectorsRequest request = new TermVectorsRequest("non-existent", "non-existent");

        ElasticsearchException exception = expectThrows(ElasticsearchException.class,
            () -> execute(request, highLevelClient()::termvectors, highLevelClient()::termvectorsAsync));
        assertEquals(RestStatus.NOT_FOUND, exception.status());
    }

    // Not entirely sure if _mtermvectors belongs to CRUD, and in the absence of a better place, will have it here
    public void testMultiTermvectors() throws IOException {
        final String sourceIndex = "index1";
        {
            // prepare : index docs
            Settings settings = Settings.builder()
                .put("number_of_shards", 1)
                .put("number_of_replicas", 0)
                .build();
            String mappings = "\"properties\":{\"field\":{\"type\":\"text\"}}";
            createIndex(sourceIndex, settings, mappings);
            assertEquals(
                RestStatus.OK,
                highLevelClient().bulk(
                    new BulkRequest()
                        .add(new IndexRequest(sourceIndex).id("1")
                            .source(Collections.singletonMap("field", "value1"), XContentType.JSON))
                        .add(new IndexRequest(sourceIndex).id("2")
                            .source(Collections.singletonMap("field", "value2"), XContentType.JSON))
                        .setRefreshPolicy(RefreshPolicy.IMMEDIATE),
                    RequestOptions.DEFAULT
                ).status()
            );
        }
        {
            // test _mtermvectors where MultiTermVectorsRequest is constructed with ids and a template
            String[] expectedIds = {"1", "2"};
            TermVectorsRequest tvRequestTemplate = new TermVectorsRequest(sourceIndex, "fake_id");
            tvRequestTemplate.setFields("field");
            MultiTermVectorsRequest mtvRequest = new MultiTermVectorsRequest(expectedIds, tvRequestTemplate);

            MultiTermVectorsResponse mtvResponse =
                execute(mtvRequest, highLevelClient()::mtermvectors, highLevelClient()::mtermvectorsAsync);

            List<String> ids = new ArrayList<>();
            for (TermVectorsResponse tvResponse: mtvResponse.getTermVectorsResponses()) {
                assertThat(tvResponse.getIndex(), equalTo(sourceIndex));
                assertTrue(tvResponse.getFound());
                ids.add(tvResponse.getId());
            }
            assertArrayEquals(expectedIds, ids.toArray());
        }

        {
            // test _mtermvectors where MultiTermVectorsRequest constructed with adding each separate request
            MultiTermVectorsRequest mtvRequest = new MultiTermVectorsRequest();
            TermVectorsRequest tvRequest1 = new TermVectorsRequest(sourceIndex, "1");
            tvRequest1.setFields("field");
            mtvRequest.add(tvRequest1);

            XContentBuilder docBuilder = XContentFactory.jsonBuilder();
            docBuilder.startObject().field("field", "valuex").endObject();
            TermVectorsRequest tvRequest2 = new TermVectorsRequest(sourceIndex, docBuilder);
            mtvRequest.add(tvRequest2);

            MultiTermVectorsResponse mtvResponse =
                execute(mtvRequest, highLevelClient()::mtermvectors, highLevelClient()::mtermvectorsAsync);
            for (TermVectorsResponse tvResponse: mtvResponse.getTermVectorsResponses()) {
                assertThat(tvResponse.getIndex(), equalTo(sourceIndex));
                assertTrue(tvResponse.getFound());
            }
        }

    }
}
