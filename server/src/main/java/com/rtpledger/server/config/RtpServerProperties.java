package com.rtpledger.server.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Getter
@Setter
@ConfigurationProperties(prefix = "rtp.server")
public class RtpServerProperties {

    private final Disruptor disruptor = new Disruptor();
    private final Nats nats = new Nats();
    private final Chronicle chronicle = new Chronicle();
    private final Drainer drainer = new Drainer();

    @Getter
    @Setter
    public static class Disruptor {
        private int ringBufferSize = 65536;
        private String threadName = "rtp-server-disruptor";
    }

    @Getter
    @Setter
    public static class Nats {
        private String servers = "nats://localhost:4222";
        private int connectionTimeoutMs = 5000;
        private String traceSubject = "ledger.trace.v1";
    }

    @Getter
    @Setter
    public static class Chronicle {
        private String mapPath = "./var/chronicle/map";
        private String queuePath = "./var/chronicle/queue";
    }

    @Getter
    @Setter
    public static class Drainer {
        private int batchSize = 500;
        private int flushIntervalMs = 50;
        private String serverId = "server-default";
    }
}
