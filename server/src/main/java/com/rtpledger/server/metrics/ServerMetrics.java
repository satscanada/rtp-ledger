package com.rtpledger.server.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * Micrometer meters for rtp-server (CP-06). Metric names use {@code rtp.server.*} prefix.
 */
@Component
public class ServerMetrics {

    private final Timer balanceComputeLatency;
    private final Timer queueAppendLatency;
    private final Timer natsReplyLatency;
    private final DistributionSummary drainerBatchSize;
    private final Timer drainerFlushLatency;
    private final Counter drainerFlushFailures;

    public ServerMetrics(MeterRegistry registry) {
        this.balanceComputeLatency = Timer.builder("rtp.server.balance.compute.latency")
                .description("Chronicle Map balance compute for one posting")
                .register(registry);
        this.queueAppendLatency = Timer.builder("rtp.server.queue.append.latency")
                .description("Chronicle Queue append for one posting")
                .register(registry);
        this.natsReplyLatency = Timer.builder("rtp.server.nats.reply.latency")
                .description("NATS transaction reply publish duration")
                .register(registry);
        this.drainerBatchSize = DistributionSummary.builder("rtp.server.drainer.batch.size")
                .description("Number of queue excerpts per drainer flush")
                .register(registry);
        this.drainerFlushLatency = Timer.builder("rtp.server.drainer.flush.latency")
                .description("JDBC batch + commit for one drainer flush")
                .register(registry);
        this.drainerFlushFailures = Counter.builder("rtp.server.drainer.flush.failures")
                .description("Drainer flush failures (rolled back)")
                .register(registry);
    }

    public void recordBalanceComputeNanos(long nanos) {
        balanceComputeLatency.record(nanos, TimeUnit.NANOSECONDS);
    }

    public void recordQueueAppendNanos(long nanos) {
        queueAppendLatency.record(nanos, TimeUnit.NANOSECONDS);
    }

    public void recordNatsReplyNanos(long nanos) {
        natsReplyLatency.record(nanos, TimeUnit.NANOSECONDS);
    }

    public void recordDrainerFlushNanos(long nanos) {
        drainerFlushLatency.record(nanos, TimeUnit.NANOSECONDS);
    }

    public void recordDrainerBatchSize(int size) {
        if (size > 0) {
            drainerBatchSize.record(size);
        }
    }

    public void incrementDrainerFlushFailures() {
        drainerFlushFailures.increment();
    }
}
