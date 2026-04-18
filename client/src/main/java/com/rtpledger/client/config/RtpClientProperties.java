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
        private int balanceRequestTimeoutMs = 500;
    }
}
