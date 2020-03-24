package org.ianitrix.kafka;

import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.awaitility.Awaitility;
import org.awaitility.Duration;
import org.ianitrix.kafka.interceptors.pojo.TraceType;
import org.ianitrix.kafka.interceptors.pojo.TracingValue;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.DockerComposeContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.File;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.ExecutionException;

@Testcontainers
@Slf4j
public class EnrichedTraceTest extends AbstractEnrichedTraceTest {

    @Container
    public static final DockerComposeContainer KAFKA =
            new DockerComposeContainer(new File("src/test/resources/compose-test.yml"))
                    .withExposedService("kafka_1", 9092, Wait.forListeningPort().withStartupTimeout(java.time.Duration.ofMinutes(3)))
                    .withLocalCompose(true);

    @BeforeAll
    public static void init() throws ExecutionException, InterruptedException {
        globalInit();
    }

    @AfterAll
    public static void shutdown() {
        globalShutdow();
    }

    @Test
    public void testSendMessage() throws ExecutionException, InterruptedException {
        subTestFirstSend();
        subTestSecondSend();

        subTestConsume();
    }



    private void subTestFirstSend() throws ExecutionException, InterruptedException {
        //send record
        this.sendRecord("test", 0, "C-ID1");

        //check trace
        Awaitility.await().atMost(Duration.FIVE_MINUTES).until(() -> traceTopicConsumer.traces.size() == 2);

        //assert
        final TracingValue send = traceTopicConsumer.traces.get(0);
        final String sendDate = send.getDate();
        final TracingValue expectedSend = TracingValue.builder()
                .correlationId("C-ID1")
                .topic("test")
                .partition(0)
                .offset(0L)
                .type(TraceType.SEND)
                .clientId("my-custom-client-id")
                .date(sendDate)
                .build();
        Assertions.assertEquals(expectedSend, send, "Send record C-ID1");

        final TracingValue ack = traceTopicConsumer.traces.get(1);
        final String ackDate = ack.getDate();
        final TracingValue expectedAck = TracingValue.builder()
                .correlationId("C-ID1")
                .topic("test")
                .partition(0)
                .offset(0L)
                .type(TraceType.ACK)
                .clientId("my-custom-client-id")
                .date(ackDate)
                .durationMs(this.computeDuration(sendDate, ackDate))
                .build();
        Assertions.assertEquals(expectedAck, ack, "Ack record C-ID1");
    }



    private void subTestSecondSend() throws ExecutionException, InterruptedException {

        //send all without correlationId
        this.sendRecord("test", 1);
        this.sendRecord("test", 1);
        this.sendRecord("test", 1);
        //send all with correlationId
        this.sendRecord("test", 1, "C-ID2");

        //check trace
        Awaitility.await().atMost(Duration.FIVE_MINUTES).until(() -> traceTopicConsumer.traces.size() == 4);

        //assert
        final TracingValue send = traceTopicConsumer.traces.get(2);
        final String sendDate = send.getDate();
        final TracingValue expectedSend = TracingValue.builder()
                .correlationId("C-ID2")
                .topic("test")
                .partition(1)
                .offset(3L)
                .type(TraceType.SEND)
                .clientId("my-custom-client-id")
                .date(sendDate)
                .build();
        Assertions.assertEquals(expectedSend, send, "Send record C-ID2");

        final TracingValue ack = traceTopicConsumer.traces.get(3);
        final String ackDate = ack.getDate();
        final TracingValue expectedAck = TracingValue.builder()
                .correlationId("C-ID2")
                .topic("test")
                .partition(1)
                .offset(3L)
                .type(TraceType.ACK)
                .clientId("my-custom-client-id")
                .date(ackDate)
                .durationMs(this.computeDuration(sendDate, ackDate))
                .build();
        Assertions.assertEquals(expectedAck, ack, "Ack record C-ID2");
    }

    private void subTestConsume() {

        //TODO: current version: if the send trace is not inside _tracing, the consume is lost because of 'inner join'

        consumeTopic(consumer1, "test", 5);

        //check trace
        Awaitility.await().atMost(Duration.FIVE_MINUTES).until(() -> traceTopicConsumer.traces.size() == 9);

        //assert
        final String sendDate1 = traceTopicConsumer.traces.get(0).getDate();
        final String sendDate2 = traceTopicConsumer.traces.get(2).getDate();


        // we can consume partition 0 or 1.
        int recordNumber = 5;
        int numberOfConsumeWithoutCorrelationIdChecked = 0;
        int numberOfConsumerWithCorrelationIdChecked = 0;

        while (recordNumber < 9) {

            final TracingValue consume = traceTopicConsumer.traces.get(recordNumber);

            if (! consume.getType().equals(TraceType.CONSUME)) {
                recordNumber++;
                continue;
            }

            if ("C-ID1".equals(consume.getCorrelationId())) {
                final String consumeDate = consume.getDate();
                final TracingValue expectedConsume = TracingValue.builder()
                        .correlationId("C-ID1")
                        .topic("test")
                        .partition(0)
                        .offset(0L)
                        .type(TraceType.CONSUME)
                        .clientId("consumer1-clientId")
                        .groupId("consumer1")
                        .date(consumeDate)
                        .durationMs(this.computeDuration(sendDate1, consumeDate))
                        .build();
                Assertions.assertEquals(expectedConsume, consume, "Consume record C-ID1");
                numberOfConsumerWithCorrelationIdChecked++;
            } else if ("C-ID2".equals(consume.getCorrelationId())) {
                final String consumeDate = consume.getDate();
                final TracingValue expectedConsume = TracingValue.builder()
                        .correlationId("C-ID2")
                        .topic("test")
                        .partition(1)
                        .offset(3L)
                        .type(TraceType.CONSUME)
                        .clientId("consumer1-clientId")
                        .groupId("consumer1")
                        .date(consumeDate)
                        .durationMs(this.computeDuration(sendDate2, consumeDate))
                        .build();
                Assertions.assertEquals(expectedConsume, consume, "Consume record C-ID2");
                numberOfConsumerWithCorrelationIdChecked++;
            } else {
                //no correlation ID on send
                final String consumeWithoutCorrelationIdDate = consume.getDate();
                final TracingValue expectedConsumeWithoutCorrelationId = TracingValue.builder()
                        .topic("test")
                        .partition(1)
                        .offset((long)numberOfConsumeWithoutCorrelationIdChecked)
                        .type(TraceType.CONSUME)
                        .clientId("consumer1-clientId")
                        .groupId("consumer1")
                        .date(consumeWithoutCorrelationIdDate)
                        .build();
                Assertions.assertEquals(expectedConsumeWithoutCorrelationId, consume, "Consume record " + numberOfConsumeWithoutCorrelationIdChecked  + " without correlationId on Send");
                numberOfConsumeWithoutCorrelationIdChecked++;
            }
            recordNumber++;
        }

        Assertions.assertEquals(2, numberOfConsumerWithCorrelationIdChecked, "2 consumer with correlation must be checked");
        Assertions.assertEquals(3, numberOfConsumerWithCorrelationIdChecked, "3 consumer without correlation must be checked");

    }

    private void consumeTopic(KafkaConsumer<String, String> consumer, final String topic, final int expectedRecordNumber) {
        consumer.subscribe(List.of(topic));
        final List<String> result = new LinkedList<>();
        while (result.size() != expectedRecordNumber) {
            final ConsumerRecords<String, String> records = consumer.poll(java.time.Duration.ofMillis(200));
            records.forEach(record -> result.add(record.value()));
        }
        consumer.commitSync();
    }
}