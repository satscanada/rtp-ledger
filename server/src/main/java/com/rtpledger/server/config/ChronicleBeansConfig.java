package com.rtpledger.server.config;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.openhft.chronicle.map.ChronicleMap;
import net.openhft.chronicle.map.ChronicleMapBuilder;
import net.openhft.chronicle.queue.impl.single.SingleChronicleQueue;
import net.openhft.chronicle.queue.impl.single.SingleChronicleQueueBuilder;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;

@Slf4j
@Configuration
@RequiredArgsConstructor
public class ChronicleBeansConfig {

    private final RtpServerProperties properties;

    @PostConstruct
    public void ensureDirectories() throws IOException {
        Files.createDirectories(new File(properties.getChronicle().getMapPath()).toPath());
        Files.createDirectories(new File(properties.getChronicle().getQueuePath()).toPath());
    }

    @Bean(destroyMethod = "close")
    public ChronicleMap<String, String> balanceChronicleMap() throws IOException {
        File mapFile = new File(properties.getChronicle().getMapPath(), "balances.dat");
        return ChronicleMapBuilder
                .of(String.class, String.class)
                .name("rtp-ledger-balances")
                .entries(1_000_000L)
                .averageKeySize(48)
                .averageValueSize(128)
                .createOrRecoverPersistedTo(mapFile);
    }

    @Bean(destroyMethod = "close")
    public SingleChronicleQueue ledgerChronicleQueue() {
        File queueDir = new File(properties.getChronicle().getQueuePath(), "ledger");
        return SingleChronicleQueueBuilder.binary(queueDir)
                .build();
    }
}
