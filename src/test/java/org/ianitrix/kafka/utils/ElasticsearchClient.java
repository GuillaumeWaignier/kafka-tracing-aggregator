package org.ianitrix.kafka.utils;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.apache.http.HttpEntity;
import org.apache.http.HttpHost;
import org.apache.http.util.EntityUtils;
import org.elasticsearch.client.Request;
import org.elasticsearch.client.Response;
import org.elasticsearch.client.RestClient;
import org.ianitrix.kafka.interceptors.pojo.TraceType;
import org.ianitrix.kafka.interceptors.pojo.TracingValue;
import org.ianitrix.kafka.pojo.ElasticsearchPojo;

import java.io.IOException;
import java.util.LinkedList;
import java.util.List;

@Slf4j
public class ElasticsearchClient {

    private static final String JSON_SIMPLE_REQUEST_TEMPLATE = "{\"query\": {\"match\": {\"%s\": \"%s\"}},\"sort\": [{\"date\": {\"order\": \"asc\"}}]}";
    private static final String JSON_BOOL_REQUEST_TEMPLATE = "{\"query\": {\"bool\": {\"filter\": [{\"match\": {\"%s\": \"%s\"}},{\"match\": {\"%s\": \"%s\"}}]}},\"sort\": [{\"date\": {\"order\": \"asc\"}}]}";
    private static final String INDEX_AGGREGATED_TRACE = "trace";
    private static final String INDEX_RAW_TRACE = "rawtrace";

    private final ObjectMapper mapper = new ObjectMapper();
    private final RestClient restClient = RestClient.builder(new HttpHost("localhost", 9200, "http")).build();



    public ElasticsearchClient() {
        this.mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }

    public void close() throws IOException {
        this.restClient.close();
    }

    public int numberOfTraces() {
        return this.numberOfTraces(INDEX_AGGREGATED_TRACE);
    }

    public int numberOfRawTraces() {
        return this.numberOfTraces(INDEX_RAW_TRACE);
    }

    private int numberOfTraces(final String indexName) {
        final Request request = new Request(
                "GET",
                "/" + indexName + "/_count");

        try {
            final Response response = restClient.performRequest(request);
            final ElasticsearchPojo elasticsearchPojo = this.convert(response.getEntity());
            return elasticsearchPojo.getCount();
        } catch (final IOException e) {
            log.error("Impossible to call Elasticsearch", e);
        }
        return 0;
    }

    public List<TracingValue> searchRawTraceByCorrelationId(final String correlationId) {
        return this.search(INDEX_RAW_TRACE, "correlationId.keyword", correlationId);
    }

    public List<TracingValue> searchRawTraceByType(final TraceType traceType) {
        return this.search(INDEX_RAW_TRACE, "type.keyword", traceType.toString());
    }

    public List<TracingValue> searchTraceByCorrelationId(final String correlationId) {
        return this.search(INDEX_AGGREGATED_TRACE, "correlationId.keyword", correlationId);
    }

    public List<TracingValue> searchTraceByType(final TraceType traceType) {
        return this.search(INDEX_AGGREGATED_TRACE, "type.keyword", traceType.toString());
    }

    private List<TracingValue> search(final String indexName, final String fieldName, final String value) {
        final Request request = new Request(
                "POST",
                "/" + indexName + "/_search");
        final String body = String.format(JSON_SIMPLE_REQUEST_TEMPLATE, fieldName, value);
        request.setJsonEntity(body);

        try {
            final Response response = restClient.performRequest(request);
            final ElasticsearchPojo elasticsearchPojo = this.convert(response.getEntity());
            return this.mapToTracingValue(elasticsearchPojo);
        } catch (final IOException e) {
            log.error("Impossible to call Elasticsearch", e);
        }
        return new LinkedList<>();
    }

    public List<TracingValue> searchRawTraceByTypeAndCorrelationId(final TraceType traceType, final String correlationId) {
        return this.multiSearch(INDEX_RAW_TRACE,"type.keyword", traceType.toString(), "correlationId.keyword", correlationId);
    }

    public List<TracingValue> searchTraceByTypeAndCorrelationId(final TraceType traceType, final String correlationId) {
        return this.multiSearch(INDEX_AGGREGATED_TRACE,"type.keyword", traceType.toString(), "correlationId.keyword", correlationId);
    }

    public List<TracingValue> multiSearch(final String indexName, final String fieldName1, final String value1, final String filedName2, final String value2) {
        final Request request = new Request(
                "POST",
                "/" + indexName + "/_search");
        final String body = String.format(JSON_BOOL_REQUEST_TEMPLATE, fieldName1, value1, filedName2, value2);
        request.setJsonEntity(body);

        try {
            final Response response = restClient.performRequest(request);
            final ElasticsearchPojo elasticsearchPojo = this.convert(response.getEntity());
            return this.mapToTracingValue(elasticsearchPojo);
        } catch (final IOException e) {
            log.error("Impossible to call Elasticsearch", e);
        }
        return new LinkedList<>();

    }

    private ElasticsearchPojo convert(final HttpEntity elasticsearchResponse) {
        try {
            final String responseBody = EntityUtils.toString(elasticsearchResponse);
            log.info("@@@@@@@ ELASTICSEARCH @@@@@@@ : " + responseBody);

            final ElasticsearchPojo elasticsearchPojo = mapper.readValue(responseBody, ElasticsearchPojo.class);
            log.info("####### READ AGGREGATED ES TRACE ###### : {}", elasticsearchPojo.toString());
            return elasticsearchPojo;
        } catch (final IOException e) {
            log.error("Impossible to convert trace {}", elasticsearchResponse, e);
        }
        return ElasticsearchPojo.builder().build();
    }

    private List<TracingValue> mapToTracingValue(final ElasticsearchPojo elasticsearchPojo) {
        final List<TracingValue> result = new LinkedList<>();
        elasticsearchPojo.getHits().getHits().forEach(hit -> result.add(hit.get_source()));
        return result;
    }
}
