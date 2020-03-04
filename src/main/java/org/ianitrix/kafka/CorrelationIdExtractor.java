package org.ianitrix.kafka;

import org.apache.kafka.common.header.Header;
import org.apache.kafka.streams.KeyValue;
import org.apache.kafka.streams.StreamsConfig;
import org.apache.kafka.streams.kstream.Transformer;
import org.apache.kafka.streams.processor.ProcessorContext;
import org.ianitrix.kafka.interceptors.AbstractTracingInterceptor;
import org.ianitrix.kafka.interceptors.pojo.TraceType;
import org.ianitrix.kafka.interceptors.pojo.TracingKey;
import org.ianitrix.kafka.interceptors.pojo.TracingValue;

import java.nio.charset.StandardCharsets;

public class CorrelationIdExtractor implements Transformer<byte[], byte[], KeyValue<TracingKey, TracingValue>> {

    private ProcessorContext context;

    @Override
    public void init(final ProcessorContext processorContext) {
        this.context = processorContext;
    }

    @Override
    public KeyValue<TracingKey, TracingValue> transform(byte[] bytes, byte[] bytes2) {

        final Header correlationIdHeader = this.context.headers().lastHeader(AbstractTracingInterceptor.CORRELATION_ID_KEY);

        // no correlationId, so drop this message
        if (correlationIdHeader == null) {
            return null;
        }

        final String correlationId = new String(correlationIdHeader.value(), StandardCharsets.UTF_8);

        final TracingKey key = TracingKey.builder()
                .correlationId(correlationId)
                .build();

        final TracingValue value = TracingValue.builder()
                .topic(this.context.topic())
                .partition(this.context.partition())
                .offset(this.context.offset())
                .correlationId(correlationId)
                .build();

        return new KeyValue<>(key, value);
    }

    @Override
    public void close() {

    }
}


