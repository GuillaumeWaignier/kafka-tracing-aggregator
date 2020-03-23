package org.ianitrix.kafka;

import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.ianitrix.kafka.interceptors.AbstractTracingInterceptor;
import org.ianitrix.kafka.interceptors.pojo.TracingValue;
import org.testcontainers.shaded.com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.time.Duration;
import java.util.*;

@Slf4j
public class TraceTopicConsumer implements Runnable {

    public final List<TracingValue> traces = new LinkedList<>();

    private KafkaConsumer<String, String> consumer;
    private final ObjectMapper mapper = new ObjectMapper();

    @Getter
    @Setter
    private boolean run = true;

    public TraceTopicConsumer(final String bootstrapServer) {
        this.createConsumer(bootstrapServer);
        new Thread(this).start();
    }

    private void createConsumer(final String bootstrapServer) {
        final Map<String, Object> config = new HashMap<>();
        config.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServer);
        config.put(ConsumerConfig.GROUP_ID_CONFIG, "junit");
        config.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        config.put(ConsumerConfig.ISOLATION_LEVEL_CONFIG, "read_committed");
        config.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        config.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        this.consumer = new KafkaConsumer<>(config);
    }

    @Override
    public void run() {

        this.consumer.subscribe(Collections.singleton(TraceTopologyStreamBuilder.OUTPUT_TRACE_TOPIC));
        while (this.run) {
            final ConsumerRecords<String, String> records = this.consumer.poll(Duration.ofMillis(200));
            records.forEach(this::convert);
        }

        this.consumer.close();
    }

    private void convert(final ConsumerRecord<String, String> record) {

        try {
            final TracingValue tracingValue = mapper.readValue(record.value(), TracingValue.class);
            this.traces.add(tracingValue);
            log.debug(tracingValue.toString());
        } catch (final IOException e) {
            log.error("Impossible to convert trace {}", record, e);
        }
    }
}
