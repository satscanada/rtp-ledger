package com.rtpledger.simulator.config;

import io.nats.client.Connection;
import io.nats.client.Nats;
import io.nats.client.Options;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;
import java.time.Duration;

@Configuration
@RequiredArgsConstructor
public class SimulatorNatsConfig {

    private final RtpSimulatorProperties properties;

    @Bean(destroyMethod = "close")
    public Connection natsConnection() throws IOException, InterruptedException {
        Options options = new Options.Builder()
                .server(properties.getNats().getServers())
                .connectionTimeout(Duration.ofMillis(properties.getNats().getConnectionTimeoutMs()))
                .build();
        return Nats.connect(options);
    }
}
