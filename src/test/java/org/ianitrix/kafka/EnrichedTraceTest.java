package org.ianitrix.kafka;

import lombok.extern.slf4j.Slf4j;
import org.awaitility.Awaitility;
import org.ianitrix.kafka.interceptors.pojo.TraceType;
import org.ianitrix.kafka.interceptors.pojo.TracingKey;
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
import java.time.Duration;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;

@Testcontainers
@Slf4j
class EnrichedTraceTest extends AbstractEnrichedTraceTest {

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
    void testSendMessage() throws ExecutionException, InterruptedException {
        subTestFirstSend();
        subTestSecondSend();

        subTestConsume();
        subTestConsumeWithNoSendTrace();

        subTestOverConsumption();
    }


    /**
     * Test the enriched SEND and ACK.
     * @throws ExecutionException
     * @throws InterruptedException
     */
    private void subTestFirstSend() throws ExecutionException, InterruptedException {
        //send record
        this.sendRecord("test", 0, "C-ID1");

        //check trace
        Awaitility.await().atMost(Duration.ofMinutes(5)).until(() -> elasticsearchClient.numberOfTraces() == 2);
        Awaitility.await().atMost(Duration.ofMinutes(5)).until(() -> elasticsearchClient.numberOfRawTraces() == 2);

        //assert
        final List<TracingValue> sends = elasticsearchClient.searchTraceByType(TraceType.SEND);
        final List<TracingValue> rawSends = elasticsearchClient.searchRawTraceByType(TraceType.SEND);
        Assertions.assertEquals(1, sends.size(), "There is 1 Send record");
        final TracingValue send = sends.get(0);
        final String sendDate = rawSends.get(0).getDate();
        final TracingValue expectedSend = TracingValue.builder()
                .id(rawSends.get(0).getId())
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
        final List<TracingValue> rawAcks = elasticsearchClient.searchRawTraceByType(TraceType.ACK);
        Assertions.assertEquals(1, acks.size(), "There is 1 ack record");
        final TracingValue ack = acks.get(0);
        final String ackDate = rawAcks.get(0).getDate();
        final TracingValue expectedAck = TracingValue.builder()
                .id(rawAcks.get(0).getId())
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


    /**
     * Test enriched SEND and ACK when there is already some send, ack with and without trace.
     * @throws ExecutionException
     * @throws InterruptedException
     */
    private void subTestSecondSend() throws ExecutionException, InterruptedException {

        //send all without correlationId
        this.sendRecord("test", 1);
        this.sendRecord("test", 1);
        this.sendRecord("test", 1);
        Thread.sleep(1);
        //send all with correlationId
        this.sendRecord("test", 1, "C-ID2");

        //check trace
        Awaitility.await().atMost(Duration.ofMinutes(5)).until(() -> elasticsearchClient.numberOfTraces() == 4);
        Awaitility.await().atMost(Duration.ofMinutes(5)).until(() -> elasticsearchClient.numberOfRawTraces() == 4);

        //assert
        final List<TracingValue> sends = elasticsearchClient.searchTraceByType(TraceType.SEND);
        final List<TracingValue> rawSends = elasticsearchClient.searchRawTraceByType(TraceType.SEND);
        Assertions.assertEquals(2, sends.size(), "There is 2 Send record");
        final TracingValue send = sends.get(1);
        final String sendDate = rawSends.get(1).getDate();
        final TracingValue expectedSend = TracingValue.builder()
                .id(rawSends.get(1).getId())
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
        final List<TracingValue> rawAcks = elasticsearchClient.searchRawTraceByType(TraceType.ACK);
        Assertions.assertEquals(2, acks.size(), "There is 2 Ack record");
        final TracingValue ack = acks.get(1);
        final String ackDate = rawAcks.get(1).getDate();
        final TracingValue expectedAck = TracingValue.builder()
                .id(rawAcks.get(1).getId())
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

    /**
     * Test the enriched CONSUME and COMMIT when the SEND trace exists.
     */
    private void subTestConsume() {

        consumeTopic(consumer1, "test", 5);

        //check All traces with correlationId C-ID1 (send, ack, consume, commit)
        Awaitility.await().atMost(Duration.ofMinutes(5)).until(() -> elasticsearchClient.searchTraceByCorrelationId("C-ID1").size() == 4);
        Awaitility.await().atMost(Duration.ofMinutes(5)).until(() -> elasticsearchClient.searchRawTraceByTypeAndCorrelationId(TraceType.CONSUME, "C-ID1").size() == 1);

        //assert
        final List<TracingValue> traceCorrelationId1 =  elasticsearchClient.searchTraceByCorrelationId("C-ID1");
        final TracingValue rawTraceConsumeCorrelationId1 =  elasticsearchClient.searchRawTraceByTypeAndCorrelationId(TraceType.CONSUME, "C-ID1").get(0);
        Assertions.assertEquals(TraceType.SEND, traceCorrelationId1.get(0).getType(), "C-ID1 : first message = send");
        Assertions.assertEquals(TraceType.ACK, traceCorrelationId1.get(1).getType(), "C-ID1 : second message = ack");
        final TracingValue consume1 = traceCorrelationId1.get(2);
        final String sendDate1 = traceTopicConsumer.traces.get(0).getDate();
        final String consumeDate1 = rawTraceConsumeCorrelationId1.getDate();
        final TracingValue expectedConsume1 = TracingValue.builder()
                .id(rawTraceConsumeCorrelationId1.getId())
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
        Awaitility.await().atMost(Duration.ofMinutes(5)).until(() -> elasticsearchClient.searchTraceByCorrelationId("C-ID2").size() == 4);
        Awaitility.await().atMost(Duration.ofMinutes(5)).until(() -> elasticsearchClient.searchRawTraceByTypeAndCorrelationId(TraceType.CONSUME, "C-ID2").size() == 1);


        //assert
        final List<TracingValue> traceCorrelationId2 =  elasticsearchClient.searchTraceByCorrelationId("C-ID2");
        final TracingValue rawTraceConsumeCorrelationId2 =  elasticsearchClient.searchRawTraceByTypeAndCorrelationId(TraceType.CONSUME, "C-ID2").get(0);
        Assertions.assertEquals(TraceType.SEND, traceCorrelationId2.get(0).getType(), "C-ID2 : first message = send");
        Assertions.assertEquals(TraceType.ACK, traceCorrelationId2.get(1).getType(), "C-ID2 : second message = ack");
        final TracingValue consume2 = traceCorrelationId2.get(2);
        final String sendDate2 = traceTopicConsumer.traces.get(2).getDate();
        final String consumeDate2 = rawTraceConsumeCorrelationId2.getDate();
        final TracingValue expectedConsume2 = TracingValue.builder()
                .id(rawTraceConsumeCorrelationId2.getId())
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
        Awaitility.await().atMost(Duration.ofMinutes(5)).until(() -> elasticsearchClient.searchRawTraceByType(TraceType.COMMIT).size() == 3);
        final TracingValue rawCommitPartition0 = elasticsearchClient.multiSearch("rawtrace", "type.keyword", TraceType.COMMIT.toString(), "partition","0").get(0);
        final TracingValue commit1 = traceCorrelationId1.get(3);
        final String commitDate1 = rawCommitPartition0.getDate();
        final TracingValue expectedCommit1 = TracingValue.builder()
                .id(rawCommitPartition0.getId())
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

        final TracingValue rawCommitPartition1 = elasticsearchClient.multiSearch("rawtrace", "type.keyword", TraceType.COMMIT.toString(), "partition","1").get(0);
        final TracingValue commit2 = traceCorrelationId2.get(3);
        final String commitDate2 = rawCommitPartition1.getDate();
        final TracingValue expectedCommit2 = TracingValue.builder()
                .id(rawCommitPartition1.getId())
                .correlationId("C-ID2")
                .topic("test")
                .partition(1)
                .offset(3L)
                .type(TraceType.COMMIT)
                .clientId("consumer1-clientId")
                .groupId("consumer1")
                .date(commitDate2)
                .durationMs(this.computeDuration(traceCorrelationId2.get(2).getDate(), commitDate2))
                .build();
        Assertions.assertEquals(expectedCommit2, commit2, "Commit partition 1 -> record C-ID2");
    }

    /**
     * Test the enriched CONSUME when the SEND trace do not exist.
     */
    private void subTestConsumeWithNoSendTrace() {
        //assert for partition 1
        final List<TracingValue> traceConsumePartition1 = elasticsearchClient.multiSearch("trace", "type.keyword", TraceType.CONSUME.toString(), "partition","1");
        final List<TracingValue> rawTraceConsumePartition1 =  elasticsearchClient.multiSearch("trace", "type.keyword", TraceType.CONSUME.toString(), "partition","1");


        Assertions.assertEquals(4, traceConsumePartition1.size(), "There are 4 consume in partition 1");
        Assertions.assertNotEquals("C-ID1", traceConsumePartition1.get(0).getCorrelationId(),"first message: no C-ID1");
        Assertions.assertNotEquals("C-ID2", traceConsumePartition1.get(0).getCorrelationId(),"first message: no C-ID2");
        Assertions.assertNotEquals("C-ID1", traceConsumePartition1.get(1).getCorrelationId(),"second message: no C-ID1");
        Assertions.assertNotEquals("C-ID2", traceConsumePartition1.get(1).getCorrelationId(),"second message: no C-ID2");
        Assertions.assertNotEquals("C-ID1", traceConsumePartition1.get(2).getCorrelationId(),"third message: no C-ID1");
        Assertions.assertNotEquals("C-ID2", traceConsumePartition1.get(2).getCorrelationId(),"third message: no C-ID2");
        Assertions.assertEquals("C-ID2", traceConsumePartition1.get(3).getCorrelationId(),"4th message: is C-ID2");


        // consume 1
        final TracingValue consume1 = traceConsumePartition1.get(0);
        final String consumeDate1 = rawTraceConsumePartition1.get(0).getDate();
        final TracingValue expectedConsume1 = TracingValue.builder()
                .id(rawTraceConsumePartition1.get(0).getId())
                .correlationId(rawTraceConsumePartition1.get(0).getCorrelationId())
                .topic("test")
                .partition(1)
                .offset(0L)
                .type(TraceType.CONSUME)
                .clientId("consumer1-clientId")
                .groupId("consumer1")
                .date(consumeDate1)
                .build();
        Assertions.assertEquals(expectedConsume1, consume1, "Consume record 1 with no SEND trace");

        // consume 2
        final TracingValue consume2 = traceConsumePartition1.get(1);
        final String consumeDate2 = rawTraceConsumePartition1.get(1).getDate();
        final TracingValue expectedConsume2 = TracingValue.builder()
                .id(rawTraceConsumePartition1.get(1).getId())
                .correlationId(rawTraceConsumePartition1.get(1).getCorrelationId())
                .topic("test")
                .partition(1)
                .offset(1L)
                .type(TraceType.CONSUME)
                .clientId("consumer1-clientId")
                .groupId("consumer1")
                .date(consumeDate2)
                .build();
        Assertions.assertEquals(expectedConsume2, consume2, "Consume record 2 with no SEND trace");

        // consume 3
        final TracingValue consume3 = traceConsumePartition1.get(2);
        final String consumeDate3 = rawTraceConsumePartition1.get(2).getDate();
        final TracingValue expectedConsume3 = TracingValue.builder()
                .id(rawTraceConsumePartition1.get(2).getId())
                .correlationId(rawTraceConsumePartition1.get(2).getCorrelationId())
                .topic("test")
                .partition(1)
                .offset(2L)
                .type(TraceType.CONSUME)
                .clientId("consumer1-clientId")
                .groupId("consumer1")
                .date(consumeDate3)
                .build();
        Assertions.assertEquals(expectedConsume3, consume3, "Consume record 3 with no SEND trace");

        // consume 4 with C-ID2
        final TracingValue rawTraceSendCorrelationId2 =  elasticsearchClient.searchRawTraceByTypeAndCorrelationId(TraceType.SEND, "C-ID2").get(0);
        final TracingValue consume4 = traceConsumePartition1.get(3);
        final String consumeDate4 = rawTraceConsumePartition1.get(3).getDate();
        final TracingValue expectedConsume4 = TracingValue.builder()
                .id(rawTraceConsumePartition1.get(3).getId())
                .correlationId("C-ID2")
                .topic("test")
                .partition(1)
                .offset(3L)
                .type(TraceType.CONSUME)
                .clientId("consumer1-clientId")
                .groupId("consumer1")
                .date(consumeDate4)
                .durationMs(this.computeDuration(rawTraceSendCorrelationId2.getDate(), consumeDate4))
                .build();
        Assertions.assertEquals(expectedConsume4, consume4, "Consume record 4 with SEND trace (duration and C-ID2)");


    }

    /**
     * Test the enriched CONSUME and COMMIT when there is over-consumption.
     */
    private void subTestOverConsumption() {

        consumeTopic(consumer2, "test", 5);
        consumer2.seekToBeginning(consumer2.assignment());
        consumeTopic(consumer2, "test", 5);

        //check All traces with consume and clientId=consumer2-clientId
        Awaitility.await().atMost(Duration.ofMinutes(5)).until(() -> elasticsearchClient.multiSearch("rawtrace", "type.keyword", TraceType.CONSUME.toString(), "clientId.keyword","consumer2-clientId").size() == 10);
        Awaitility.await().atMost(Duration.ofMinutes(5)).until(() -> elasticsearchClient.multiSearch("trace", "type.keyword", TraceType.CONSUME.toString(), "clientId.keyword","consumer2-clientId").size() == 10);

        //assert consume
        final List<TracingValue> traceConsume =  elasticsearchClient.multiSearch("trace", "type.keyword", TraceType.CONSUME.toString(), "clientId.keyword","consumer2-clientId");

        final Map<TracingKey, List<TracingValue>> consumeTraceByKey = new HashMap<>();

        for (final TracingValue consume : traceConsume) {

            final TracingKey key = TracingKey.builder()
                    .topic(consume.getTopic())
                    .partition(consume.getPartition())
                    .offset(consume.getOffset())
                    .build();
            final List<TracingValue> traceWithGivenKey = consumeTraceByKey.getOrDefault(key, new LinkedList<>());
            traceWithGivenKey.add(consume);
            consumeTraceByKey.put(key, traceWithGivenKey);

            final String traceId = consume.getId();
            final TracingValue rawTrace = elasticsearchClient.multiSearch("rawtrace", "type.keyword", TraceType.CONSUME.toString(), "id.keyword",traceId).get(0);
            final List<TracingValue> send = elasticsearchClient.multiSearch("rawtrace", "type.keyword", TraceType.SEND.toString(), "correlationId.keyword", rawTrace.getCorrelationId());
            String sendDate = null;
            if (!send.isEmpty()) {
                sendDate = send.get(0).getDate();
            }

            final TracingValue expectedConsume = TracingValue.builder()
                    .id(traceId)
                    .correlationId(rawTrace.getCorrelationId())
                    .topic("test")
                    .partition(rawTrace.getPartition())
                    .offset(rawTrace.getOffset())
                    .type(TraceType.CONSUME)
                    .clientId("consumer2-clientId")
                    .groupId("consumer2")
                    .date(rawTrace.getDate())
                    .build();
            if (sendDate != null) {
                expectedConsume.setDurationMs(this.computeDuration(sendDate, rawTrace.getDate()));
            }
            Assertions.assertEquals(expectedConsume, consume, "Consume record trace with id " + traceId);
        }

        // There must be 2 consume for each tuple (topic, partition, offset)
        Assertions.assertEquals(5, consumeTraceByKey.size(), "There is 5 different records");
        for(final TracingKey key : consumeTraceByKey.keySet()) {
            Assertions.assertEquals(2, consumeTraceByKey.get(key).size(), "There is 2 consume for the key " + key);
        }



        // check commit
        final List<TracingValue> traceCommit =  elasticsearchClient.multiSearch("trace", "type.keyword", TraceType.COMMIT.toString(), "clientId.keyword","consumer2-clientId");

        final Map<TracingKey, Integer> positionOfConsumeByKey = new HashMap<>();
        consumeTraceByKey.forEach((tracingKey, tracingValues) -> positionOfConsumeByKey.put(tracingKey, 0));

        for (final TracingValue commit : traceCommit) {
            final String traceId = commit.getId();
            final TracingValue rawTrace = elasticsearchClient.multiSearch("rawtrace", "type.keyword", TraceType.COMMIT.toString(), "id.keyword",traceId).get(0);

            final TracingKey key = TracingKey.builder()
                    .topic(commit.getTopic())
                    .partition(commit.getPartition())
                    .offset(commit.getOffset())
                    .build();

            final TracingValue expectedCommit = TracingValue.builder()
                    .id(traceId)
                    .correlationId(commit.getCorrelationId())
                    .topic("test")
                    .partition(rawTrace.getPartition())
                    .offset(rawTrace.getOffset())
                    .type(TraceType.COMMIT)
                    .clientId("consumer2-clientId")
                    .groupId("consumer2")
                    .date(rawTrace.getDate())
                    .build();

            // Find associated consume
            if (commit.getOffset() == -1) {
                //there is no associated consume
                log.info("Commit {} has no associated consume", commit);
            } else {
                final Integer pos = positionOfConsumeByKey.get(key);
                final TracingValue consume = consumeTraceByKey.get(key).get(pos);
                log.info("Commit {} is associated with consumme {} at position {}", commit, consume, pos);
                positionOfConsumeByKey.put(key, pos + 1);
                expectedCommit.setDurationMs(this.computeDuration(consume.getDate(), rawTrace.getDate()));
            }

            Assertions.assertEquals(expectedCommit, commit, "Commit record trace with key " + key + " and id " + traceId);
        }
    }
}