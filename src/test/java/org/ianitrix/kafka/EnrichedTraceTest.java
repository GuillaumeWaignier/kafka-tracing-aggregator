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
import java.io.IOException;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.ExecutionException;

@Testcontainers
@Slf4j
public class EnrichedTraceTest extends AbstractEnrichedTraceTest {

    @Container
    public static final DockerComposeContainer KAFKA =
            new DockerComposeContainer(new File("src/test/resources/compose-test.yml"))
                    .withExposedService("kafka_1", 9092, Wait.forListeningPort().withStartupTimeout(java.time.Duration.ofMinutes(5)))
                    .withExposedService("elasticsearch_1", 9200, Wait.forListeningPort().withStartupTimeout(java.time.Duration.ofMinutes(5)))
                    .withLocalCompose(true);

    @BeforeAll
    public static void init() throws ExecutionException, InterruptedException {
        globalInit();
    }

    @AfterAll
    public static void shutdown() throws IOException {
        globalShutdown();
    }

    @Test
    public void testSendMessage() throws ExecutionException, InterruptedException, IOException {
        subTestFirstSend();
        subTestSecondSend();

        subTestConsume();
    }



    private void subTestFirstSend() throws ExecutionException, InterruptedException, IOException {
        //send record
        this.sendRecord("test", 0, "C-ID1");

        //check trace
        Awaitility.await().atMost(Duration.FIVE_MINUTES).until(() -> elasticsearchClient.numberOfTraces() == 2);

        //assert
        final List<TracingValue> sends = elasticsearchClient.searchTraceByType(TraceType.SEND);
        Assertions.assertEquals(1, sends.size(), "There is 1 Send record");
        final TracingValue send = sends.get(0);
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

        final List<TracingValue> acks = elasticsearchClient.searchTraceByType(TraceType.ACK);
        Assertions.assertEquals(1, acks.size(), "There is 1 ack record");
        final TracingValue ack = acks.get(0);
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
        Awaitility.await().atMost(Duration.FIVE_MINUTES).until(() -> elasticsearchClient.numberOfTraces() == 4);

        //assert
        final List<TracingValue> sends = elasticsearchClient.searchTraceByType(TraceType.SEND);
        Assertions.assertEquals(2, sends.size(), "There is 2 Send record");
        final TracingValue send = sends.get(1);
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

        final List<TracingValue> acks = elasticsearchClient.searchTraceByType(TraceType.ACK);
        Assertions.assertEquals(2, acks.size(), "There is 2 Ack record");
        final TracingValue ack = acks.get(1);
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

        //check All traces with correlationId C-ID1 (send, ack, consume, commit)
        Awaitility.await().atMost(Duration.FIVE_MINUTES).until(() -> elasticsearchClient.searchTraceByCorrelationId("C-ID1").size() == 4);

        //assert
        final List<TracingValue> traceCorrelationId1 =  elasticsearchClient.searchTraceByCorrelationId("C-ID1");
        Assertions.assertEquals(TraceType.SEND, traceCorrelationId1.get(0).getType(), "C-ID1 : first message = send");
        Assertions.assertEquals(TraceType.ACK, traceCorrelationId1.get(1).getType(), "C-ID1 : second message = ack");
        final TracingValue consume1 = traceCorrelationId1.get(2);
        final String sendDate1 = traceTopicConsumer.traces.get(0).getDate();
        final String consumeDate1 = consume1.getDate();
        final TracingValue expectedConsume1 = TracingValue.builder()
                .correlationId("C-ID1")
                .topic("test")
                .partition(0)
                .offset(0L)
                .type(TraceType.CONSUME)
                .clientId("consumer1-clientId")
                .groupId("consumer1")
                .date(consumeDate1)
                .durationMs(this.computeDuration(sendDate1, consumeDate1))
                .build();
        Assertions.assertEquals(expectedConsume1, consume1, "Consume record C-ID1");
        Assertions.assertEquals(TraceType.COMMIT, traceCorrelationId1.get(3).getType(), "C-ID1 : second message = commit");

        //check All traces with correlationId C-ID2 (send, ack, consume, commit)
        Awaitility.await().atMost(Duration.FIVE_MINUTES).until(() -> elasticsearchClient.searchTraceByCorrelationId("C-ID2").size() == 4);

        //assert
        final List<TracingValue> traceCorrelationId2 =  elasticsearchClient.searchTraceByCorrelationId("C-ID2");
        Assertions.assertEquals(TraceType.SEND, traceCorrelationId2.get(0).getType(), "C-ID2 : first message = send");
        Assertions.assertEquals(TraceType.ACK, traceCorrelationId2.get(1).getType(), "C-ID2 : second message = ack");
        final TracingValue consume2 = traceCorrelationId2.get(2);
        final String sendDate2 = traceTopicConsumer.traces.get(2).getDate();
        final String consumeDate2 = consume2.getDate();
        final TracingValue expectedConsume2 = TracingValue.builder()
                .correlationId("C-ID2")
                .topic("test")
                .partition(1)
                .offset(3L)
                .type(TraceType.CONSUME)
                .clientId("consumer1-clientId")
                .groupId("consumer1")
                .date(consumeDate2)
                .durationMs(this.computeDuration(sendDate2, consumeDate2))
                .build();
        Assertions.assertEquals(expectedConsume2, consume2, "Consume record C-ID2");
        Assertions.assertEquals(TraceType.COMMIT, traceCorrelationId2.get(3).getType(), "C-ID2 : second message = commit");

        //check commit
        // there is one commit for each partition
        final TracingValue commit1 = traceCorrelationId1.get(3);
        final String commitDate1 = commit1.getDate();
        final TracingValue expectedCommit1 = TracingValue.builder()
                .correlationId("C-ID1")
                .topic("test")
                .partition(0)
                .offset(0L)
                .type(TraceType.COMMIT)
                .clientId("consumer1-clientId")
                .groupId("consumer1")
                .date(commitDate1)
                .durationMs(this.computeDuration(traceCorrelationId1.get(2).getDate(), commitDate1))
                .build();
        Assertions.assertEquals(expectedCommit1, commit1, "Commit partition 0 -> record C-ID1");

        final TracingValue commit2 = traceCorrelationId2.get(3);
        final String commitDate2 = commit2.getDate();
        final TracingValue expectedCommit2 = TracingValue.builder()
                .correlationId("C-ID2")
                .topic("test")
                .partition(1)
                .offset(3L)
                .type(TraceType.COMMIT)
                .clientId("consumer1-clientId")
                .groupId("consumer1")
                .date(commitDate2)
                .durationMs(this.computeDuration(traceCorrelationId1.get(2).getDate(), commitDate2))
                .build();
        Assertions.assertEquals(expectedCommit2, commit2, "Commit partition 1 -> record C-ID2");
    }
}