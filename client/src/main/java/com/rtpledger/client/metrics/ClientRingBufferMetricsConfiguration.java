package com.rtpledger.client.metrics;

import com.lmax.disruptor.RingBuffer;
import com.rtpledger.client.disruptor.TransactionEvent;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Ring buffer gauge is registered after the {@link RingBuffer} bean exists, avoiding a
 * {@code ClientMetrics} ↔ {@code NatsPublishEventHandler} ↔ {@code RingBuffer} cycle.
 */
@Configuration
public class ClientRingBufferMetricsConfiguration {

    @Bean
    public Gauge rtpClientDisruptorRingRemaining(MeterRegistry registry, RingBuffer<TransactionEvent> ringBuffer) {
        return Gauge.builder("rtp.client.disruptor.ring.remaining", ringBuffer, RingBuffer::remainingCapacity)
                .description("Remaining capacity in the Disruptor ring buffer")
                .register(registry);
    }
}
