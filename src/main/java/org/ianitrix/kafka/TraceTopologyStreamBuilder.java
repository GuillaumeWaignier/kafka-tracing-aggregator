package org.ianitrix.kafka;

import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.common.serialization.Serde;
import org.apache.kafka.streams.StreamsBuilder;
import org.apache.kafka.streams.Topology;
import org.apache.kafka.streams.kstream.*;
import org.apache.kafka.streams.state.Stores;
import org.ianitrix.kafka.interceptors.AbstractTracingInterceptor;
import org.ianitrix.kafka.interceptors.pojo.TraceType;
import org.ianitrix.kafka.interceptors.pojo.TracingKey;
import org.ianitrix.kafka.interceptors.pojo.TracingValue;

import java.time.Duration;
import java.time.Instant;

@Slf4j
public class TraceTopologyStreamBuilder {

    public static final String OUTPUT_TRACE_TOPIC = "_aggregatedTrace";

    private final Serde<TracingKey> tracingKeySerde = SerdesUtils.createJsonSerdes(TracingKey.class);
    private final Serde<TracingValue> tracingValueSerde = SerdesUtils.createJsonSerdes(TracingValue.class);

    public Topology buildStream() {

        final StreamsBuilder builder = new StreamsBuilder();

        // Stream containing all traces
        final KStream<TracingKey, TracingValue> tracesStream = builder.stream(AbstractTracingInterceptor.TRACE_TOPIC, Consumed.with(tracingKeySerde, tracingValueSerde));

        final KStream<TracingKey, TracingValue>[] splittedStream = tracesStream.branch(Named.as("splitByType"),(tracingKey, tracingValue) -> tracingValue.getType().equals(TraceType.SEND),
                (tracingKey, tracingValue) -> tracingValue.getType().equals(TraceType.ACK),
                (tracingKey, tracingValue) -> tracingValue.getType().equals(TraceType.CONSUME));
        final KStream<TracingKey, TracingValue> sendTraceStream = splittedStream[0];
        final KStream<TracingKey, TracingValue> ackTraceStream = splittedStream[1];
        final KStream<TracingKey, TracingValue> consumeTraceStream = splittedStream[2];


        // Stream for all messages
        //final KStream<byte[], byte[]> allStream = builder.stream(Pattern.compile("^[a-zA-Z0-9].*"));
        final KStream<byte[], byte[]> allStream = builder.stream("test");
        final KStream<TracingKey, TracingValue> allStreamWithCorrelationIdKey = allStream.transform(() -> new CorrelationIdExtractor(), Named.as("allMessagesWithCorrelationIdKey"));

        // send
        final KStream<TracingKey, TracingValue> enrichedSendWithCorrelationIdKey = sendTraceStream.join(allStreamWithCorrelationIdKey,
                this::enrichSend,
                JoinWindows.of(Duration.ofMinutes(5)),
                StreamJoined.with(tracingKeySerde, tracingValueSerde, tracingValueSerde).withName("send")
        );
        final KStream<TracingKey, TracingValue> enrichedSend = enrichedSendWithCorrelationIdKey.selectKey(this::createTopicPartitionOffsetKey);
        enrichedSend.to(OUTPUT_TRACE_TOPIC, Produced.with(tracingKeySerde, tracingValueSerde));

        //ack
        final KStream<TracingKey, TracingValue> enrichedAck = ackTraceStream.join(enrichedSend,
                this::enrichAck,
                JoinWindows.of(Duration.ofMinutes(2)),
                StreamJoined.with(tracingKeySerde, tracingValueSerde, tracingValueSerde).withName("ack"));
        enrichedAck.to(OUTPUT_TRACE_TOPIC, Produced.with(tracingKeySerde, tracingValueSerde));

        //commit
        builder.addStateStore(
                Stores.keyValueStoreBuilder(
                        Stores.persistentKeyValueStore(CommitMerger.CONSUME_STORE_NAME), tracingKeySerde, tracingValueSerde));
        final KStream<TracingKey, TracingValue> enrichedCommit = tracesStream.transform(() -> new CommitMerger(), Named.as("commit"), CommitMerger.CONSUME_STORE_NAME);
        enrichedCommit.to(OUTPUT_TRACE_TOPIC, Produced.with(tracingKeySerde, tracingValueSerde));

        // consume
        final KStream<TracingKey, TracingValue> enrichedConsume = consumeTraceStream
                .selectKey(this::createTopicPartitionOffsetKey)
                .leftJoin(enrichedSend,
                this::enrichConsume,
                JoinWindows.of(Duration.ofDays(1)),
                StreamJoined.with(tracingKeySerde, tracingValueSerde, tracingValueSerde).withName("consume"));
        enrichedConsume.to(OUTPUT_TRACE_TOPIC, Produced.with(tracingKeySerde, tracingValueSerde));

        return builder.build();
    }

    private TracingValue enrichSend(final TracingValue send, final TracingValue allMessages) {
        send.setTopic(allMessages.getTopic());
        send.setPartition(allMessages.getPartition());
        send.setOffset(allMessages.getOffset());
        return send;
    }

    private TracingKey createTopicPartitionOffsetKey(final TracingKey tracingKey, final TracingValue tracingValue) {
        return TracingKey.builder()
                .topic(tracingValue.getTopic())
                .partition(tracingValue.getPartition())
                .offset(tracingValue.getOffset())
                .build();
    }

    private TracingValue enrichAck(final TracingValue ack, final TracingValue send) {
        ack.setCorrelationId(send.getCorrelationId());

        final Duration duration = Duration.between(Instant.parse(send.getDate()), Instant.parse(ack.getDate()));
        ack.setDurationMs(duration.toMillis());

        return ack;
    }

    private TracingValue enrichConsume(final TracingValue consume, final TracingValue send) {

        if (send == null) {
            // send is performed too far in the past, so it is delete : do not compute duration for consume
            return consume;
        }
        final Duration duration = Duration.between(Instant.parse(send.getDate()), Instant.parse(consume.getDate()));
        consume.setDurationMs(duration.toMillis());

        return consume;
    }

    private TracingValue enrichCommit(final TracingValue commit, final TracingValue consume) {

        // for example when offset = -1, or when the client set change the offset (there is no duration)
        if (consume == null) {
            return commit;
        }

        commit.setCorrelationId(consume.getCorrelationId());

        log.info("&&&&&&&&&&& fusion {}  &&&&&& {} ", consume, commit);

        final Duration duration = Duration.between(Instant.parse(consume.getDate()), Instant.parse(commit.getDate()));
        commit.setDurationMs(duration.toMillis());

        return commit;

    }
}
