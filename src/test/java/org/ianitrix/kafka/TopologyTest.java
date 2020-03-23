package org.ianitrix.kafka;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.CreateTopicsResult;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;
import org.apache.kafka.streams.KafkaStreams;
import org.apache.kafka.streams.StreamsConfig;
import org.apache.kafka.streams.Topology;
import org.awaitility.Awaitility;
import org.awaitility.Duration;
import org.ianitrix.kafka.interceptors.AbstractTracingInterceptor;
import org.ianitrix.kafka.interceptors.pojo.TraceType;
import org.ianitrix.kafka.interceptors.pojo.TracingKey;
import org.ianitrix.kafka.interceptors.pojo.TracingValue;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.DockerComposeContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ExecutionException;

@Testcontainers
@Slf4j
public class TopologyTest {

    private static final String KAFKA_BOOTSTRAP_SERVER = "localhost:9092";

    private static final com.fasterxml.jackson.databind.ObjectMapper mapper = new ObjectMapper();


    @Container
    public static final DockerComposeContainer kafka =
            new DockerComposeContainer(new File("src/test/resources/compose-test.yml"))
                    .withExposedService("kafka_1", 9092, Wait.forListeningPort().withStartupTimeout(java.time.Duration.ofMinutes(3)))
                    .withLocalCompose(true);


    private static KafkaProducer<String, String> producer;
    private static TraceTopicConsumer traceTopicConsumer;

    @BeforeAll
    public static void globalInit() throws ExecutionException, InterruptedException {
        //mapper.setSerializationInclusion(JsonInclude.Include.NON_NULL);

        createTopic();
        createProducer();
        traceTopicConsumer = new TraceTopicConsumer(KAFKA_BOOTSTRAP_SERVER);
        createKstreamApplication();
    }

    private static void createTopic() throws ExecutionException, InterruptedException {
        final Map<String, Object> config = new HashMap<>();
        config.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA_BOOTSTRAP_SERVER);
        AdminClient admin = AdminClient.create(config);
        final CreateTopicsResult topicsResult = admin.createTopics(List.of(new NewTopic(AbstractTracingInterceptor.TRACE_TOPIC, 3, (short) 1),
                new NewTopic("test", 3, (short) 1)));
        topicsResult.all().get();
    }

    private static void createKstreamApplication() {
        final Properties config = AggregatorTraceStream.computeStreamConfig("src/test/resources/config.properties");
        config.put(StreamsConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA_BOOTSTRAP_SERVER);
        final Topology topology = new TraceTopologyStreamBuilder().buildStream();
        log.info(topology.describe().toString());

        final KafkaStreams streams = new KafkaStreams(topology, config);
        streams.start();
    }

    private static void createProducer() {
        final Map<String, Object> config = new HashMap<>();
        config.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA_BOOTSTRAP_SERVER);
        config.put(ProducerConfig.ACKS_CONFIG, "all");
        config.put(ProducerConfig.CLIENT_ID_CONFIG, "junitRawTraceProducer");
        config.put(ProducerConfig.COMPRESSION_TYPE_CONFIG, "zstd");
        config.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        config.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        producer = new KafkaProducer<>(config);
    }

    @Test
    public void testSendMessage() throws JsonProcessingException {

        //send trace
        final Instant now = Instant.now();
        this.sendRawTrace(TracingKey.builder()
                        .correlationId("C-ID1").build(),
                TracingValue.builder()
                        .topic("test")
                        .correlationId("C-ID1")
                        .clientId("P1")
                        .type(TraceType.SEND)
                        .date(now.toString()).build());

        //send all
        this.sendRecord("test", 0, "C-ID1");

        //check trace
        Awaitility.await().atMost(Duration.FIVE_MINUTES).until(() -> traceTopicConsumer.traces.size() == 1);

        //assert
        final TracingValue send = traceTopicConsumer.traces.get(0);
        final TracingValue expectedSend = TracingValue.builder()
                .correlationId("C-ID1")
                .topic("test")
                .partition(0)
                .offset(0L)
                .type(TraceType.SEND)
                .clientId("P1")
                .date(now.toString())
                .build();
        Assertions.assertEquals(expectedSend, send);



    }

    private void sendRawTrace(final TracingKey tracingKey, final TracingValue tracingValue) throws com.fasterxml.jackson.core.JsonProcessingException {
        final String jsonKey = mapper.writeValueAsString(tracingKey);
        final String jsonValue = mapper.writeValueAsString(tracingValue);
        producer.send(new ProducerRecord<>(AbstractTracingInterceptor.TRACE_TOPIC, jsonKey, jsonValue));
    }

    private void sendRecord(final String topic, int partition, final String correlationId) {
        final ProducerRecord<String, String> record = new ProducerRecord<>(topic, partition, UUID.randomUUID().toString(), UUID.randomUUID().toString());
        record.headers().add(AbstractTracingInterceptor.CORRELATION_ID_KEY, correlationId.getBytes(StandardCharsets.UTF_8));
        producer.send(record);
    }
}