package org.ianitrix.kafka;

import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.CreateTopicsResult;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.apache.kafka.streams.KafkaStreams;
import org.apache.kafka.streams.StreamsConfig;
import org.apache.kafka.streams.Topology;
import org.ianitrix.kafka.interceptors.AbstractTracingInterceptor;
import org.ianitrix.kafka.interceptors.ConsumerTracingInterceptor;
import org.ianitrix.kafka.interceptors.ProducerTracingInterceptor;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ExecutionException;

@Slf4j
public class AbstractEnrichedTraceTest {

    private static final String KAFKA_BOOTSTRAP_SERVER = "localhost:9092";

    private static KafkaProducer<String, String> producerWithInterceptor;
    private static KafkaProducer<String, String> producer;
    protected static KafkaConsumer<String, String> consumer1;
    protected static KafkaConsumer<String, String> consumer2;

    protected static TraceTopicConsumer traceTopicConsumer;
    private static KafkaStreams streams;


    public static void globalInit() throws ExecutionException, InterruptedException {
        createTopic();
        createProducers();
        createConsumers();
        traceTopicConsumer = new TraceTopicConsumer(KAFKA_BOOTSTRAP_SERVER);
        createKstreamApplication();
    }

    public static void globalShutdow() {
        consumer1.close();
        consumer2.close();
        producer.close();
        producerWithInterceptor.close();
        streams.close();
    }

    private static void createTopic() throws ExecutionException, InterruptedException {
        final Map<String, Object> config = new HashMap<>();
        config.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA_BOOTSTRAP_SERVER);
        final AdminClient admin = AdminClient.create(config);
        final CreateTopicsResult topicsResult = admin.createTopics(List.of(new NewTopic(AbstractTracingInterceptor.TRACE_TOPIC, 3, (short) 1),
                new NewTopic("test", 3, (short) 1)));
        topicsResult.all().get();
        admin.close();
    }

    private static void createKstreamApplication() {
        final Properties config = AggregatorTraceStream.computeStreamConfig("src/test/resources/config.properties");
        config.put(StreamsConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA_BOOTSTRAP_SERVER);
        config.put(StreamsConfig.STATE_DIR_CONFIG, "./target/storeSend");
        final Topology topology = new TraceTopologyStreamBuilder().buildStream();
        log.info(topology.describe().toString());

        streams = new KafkaStreams(topology, config);
        streams.start();
    }

    private static void createProducers() {
        final Map<String, Object> config = new HashMap<>();
        config.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA_BOOTSTRAP_SERVER);
        config.put(ProducerConfig.ACKS_CONFIG, "all");
        config.put(ProducerConfig.CLIENT_ID_CONFIG, "my-custom-client-id");
        config.put(ProducerConfig.COMPRESSION_TYPE_CONFIG, "zstd");
        config.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        config.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);

        producer = new KafkaProducer<>(config);

        config.put(ProducerConfig.INTERCEPTOR_CLASSES_CONFIG, ProducerTracingInterceptor.class.getName());
        producerWithInterceptor = new KafkaProducer<>(config);
    }

    private static void createConsumers() {
        final Map<String, Object> config = new HashMap<>();
        config.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA_BOOTSTRAP_SERVER);
        config.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        config.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        config.put(ConsumerConfig.ISOLATION_LEVEL_CONFIG, "read_committed");
        config.put(ConsumerConfig.CLIENT_ID_CONFIG, "consumer1-clientId");
        config.put(ConsumerConfig.GROUP_ID_CONFIG, "consumer1");
        config.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        config.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        config.put(ConsumerConfig.INTERCEPTOR_CLASSES_CONFIG, ConsumerTracingInterceptor.class.getName());

        consumer1 = new KafkaConsumer<>(config);

        config.put(ConsumerConfig.CLIENT_ID_CONFIG, "consumer2-clientId");
        config.put(ConsumerConfig.GROUP_ID_CONFIG, "consumer2");
        consumer2 = new KafkaConsumer<>(config);
    }




    protected void sendRecord(final String topic, int partition, final String correlationId) throws ExecutionException, InterruptedException {
        final ProducerRecord<String, String> record = new ProducerRecord<>(topic, partition, UUID.randomUUID().toString(), UUID.randomUUID().toString());
        record.headers().add(AbstractTracingInterceptor.CORRELATION_ID_KEY, correlationId.getBytes(StandardCharsets.UTF_8));
        producerWithInterceptor.send(record).get();
    }

    protected void sendRecord(String topic, int partition) throws ExecutionException, InterruptedException {
        producer.send(new ProducerRecord<>(topic, partition, UUID.randomUUID().toString(), UUID.randomUUID().toString())).get();
    }

    protected Long computeDuration(String startDate, String endDate) {
        return java.time.Duration.between(Instant.parse(startDate), Instant.parse(endDate)).toMillis();
    }
}