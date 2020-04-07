package org.ianitrix.kafka;

import org.apache.kafka.common.header.Header;
import org.apache.kafka.streams.KeyValue;
import org.apache.kafka.streams.kstream.Transformer;
import org.apache.kafka.streams.processor.ProcessorContext;
import org.apache.kafka.streams.state.KeyValueStore;
import org.ianitrix.kafka.interceptors.AbstractTracingInterceptor;
import org.ianitrix.kafka.interceptors.pojo.TraceType;
import org.ianitrix.kafka.interceptors.pojo.TracingKey;
import org.ianitrix.kafka.interceptors.pojo.TracingValue;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;

public class AckMerger implements Transformer<TracingKey, TracingValue, KeyValue<TracingKey, TracingValue>> {

    public static final String CONSUME_STORE_NAME = "consume";

    private KeyValueStore<TracingKey, TracingValue> consumeStore;

    @Override
    public void init(final ProcessorContext processorContext) {
        this.consumeStore = (KeyValueStore<TracingKey, TracingValue>) processorContext.getStateStore(CONSUME_STORE_NAME);
    }

    @Override
    public KeyValue<TracingKey, TracingValue> transform(final TracingKey key, final TracingValue value) {

        if (value.getType().equals(TraceType.CONSUME)) {
            this.consumeStore.put(key, value);
            return null;
        } else if (value.getType().equals(TraceType.COMMIT)) {

            final TracingValue consume = this.consumeStore.get(key);

            // for example when offset = -1, or when the client set change the offset (there is no duration)
            if (consume == null) {
                return new KeyValue<>(key, value);
            }

            value.setCorrelationId(consume.getCorrelationId());

            //log.info("&&&&&&&&&&& fusion {}  &&&&&& {} ", consume, commit);

            final Duration duration = Duration.between(Instant.parse(consume.getDate()), Instant.parse(value.getDate()));
            value.setDurationMs(duration.toMillis());

            this.consumeStore.delete(key);

            return new KeyValue<>(key, value);
        }
        return null;
    }

    @Override
    public void close() {

    }
}


