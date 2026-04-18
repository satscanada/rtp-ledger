package com.rtpledger.server.config;

import io.nats.client.Connection;
import io.nats.client.Nats;
import io.nats.client.Options;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;

@Configuration
@RequiredArgsConstructor
public class ServerNatsConfig {

    private final RtpServerProperties properties;

    @Bean(destroyMethod = "close")
    public Connection natsConnection() throws IOException, InterruptedException {
        Options options = new Options.Builder()
                .server(properties.getNats().getServers())
                .connectionTimeout(java.time.Duration.ofMillis(properties.getNats().getConnectionTimeoutMs()))
                .build();
        return Nats.connect(options);
    }
}
