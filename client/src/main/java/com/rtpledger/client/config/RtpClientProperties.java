package com.rtpledger.client.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Getter
@Setter
@ConfigurationProperties(prefix = "rtp.client")
public class RtpClientProperties {

    private final Disruptor disruptor = new Disruptor();
    private final Nats nats = new Nats();
    private final Trace trace = new Trace();

    @Getter
    @Setter
    public static class Disruptor {
        private int ringBufferSize = 65536;
        private String threadName = "rtp-client-disruptor";
    }

    @Getter
    @Setter
    public static class Nats {
        private String servers = "nats://localhost:4222";
        private int connectionTimeoutMs = 5000;
        private String subjectPrefix = "ledger";
        private String traceSubject = "ledger.trace.v1";
        private int balanceRequestTimeoutMs = 500;
    }

    @Getter
    @Setter
    public static class Trace {
        private int maxCorrelations = 5000;
        private int maxEventsPerCorrelation = 64;
        private int retentionMinutes = 30;
        private int recentLimit = 100;
    }
}
