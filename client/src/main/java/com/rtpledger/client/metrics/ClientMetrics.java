package com.rtpledger.client.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

/**
 * Micrometer meters for rtp-client (CP-06). Metric names match {@code rtp.client.*} convention.
 */
@Component
public class ClientMetrics {

    private final MeterRegistry registry;
    private final Timer disruptorPublishLatency;
    private final Timer natsPublishLatency;
    private final Counter natsPublishFailures;
    private final Timer balanceQueryLatency;

    public ClientMetrics(MeterRegistry registry) {
        this.registry = registry;
        this.disruptorPublishLatency = Timer.builder("rtp.client.disruptor.publish.latency")
                .description("Time to claim and publish one event on the Disruptor ring buffer")
                .register(registry);
        this.natsPublishLatency = Timer.builder("rtp.client.nats.publish.latency")
                .description("Time to serialize and NATS.publish one posting")
                .register(registry);
        this.natsPublishFailures = Counter.builder("rtp.client.nats.publish.failures")
                .description("NATS publish failures in the Disruptor event handler")
                .register(registry);
        this.balanceQueryLatency = Timer.builder("rtp.client.balance.query.latency")
                .description("NATS request roundtrip for GET balance")
                .register(registry);
    }

    public void recordNatsPublishLatency(Runnable publishAction) {
        natsPublishLatency.record(publishAction);
    }

    public void incrementTransactionsAccepted(String region) {
        registry.counter("rtp.client.transactions.accepted", "region", region).increment();
    }

    public void incrementTransactionsRejected(String reason) {
        String safe = reason == null ? "unknown" : reason;
        if (safe.length() > 64) {
            safe = safe.substring(0, 64);
        }
        registry.counter("rtp.client.transactions.rejected", "reason", safe).increment();
    }

    public Timer.Sample startDisruptorPublishSample() {
        return Timer.start(registry);
    }

    public void recordDisruptorPublishLatency(Timer.Sample sample) {
        sample.stop(disruptorPublishLatency);
    }

    public void incrementNatsPublishFailures() {
        natsPublishFailures.increment();
    }

    public void recordBalanceNanos(long nanos) {
        balanceQueryLatency.record(nanos, java.util.concurrent.TimeUnit.NANOSECONDS);
    }
}
