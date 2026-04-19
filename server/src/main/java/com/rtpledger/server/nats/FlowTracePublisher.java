package com.rtpledger.server.nats;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rtpledger.server.config.RtpServerProperties;
import com.rtpledger.shared.message.FlowTraceEvent;
import io.micrometer.core.instrument.MeterRegistry;
import io.nats.client.Connection;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

@Slf4j
@Component
@RequiredArgsConstructor
public class FlowTracePublisher {

    private final Connection natsConnection;
    private final ObjectMapper objectMapper;
    private final RtpServerProperties properties;
    private final MeterRegistry meterRegistry;

    private final BlockingQueue<FlowTraceEvent> queue = new LinkedBlockingQueue<>(4096);
    private volatile boolean running = true;
    private Thread worker;

    @PostConstruct
    public void start() {
        worker = new Thread(this::drain, "rtp-server-trace-publisher");
        worker.setDaemon(true);
        worker.start();
    }

    @PreDestroy
    public void stop() {
        running = false;
        worker.interrupt();
    }

    public void publish(FlowTraceEvent event) {
        if (event == null) {
            return;
        }
        queue.offer(event); // drops silently if full — trace is advisory
    }

    private void drain() {
        while (running) {
            try {
                FlowTraceEvent event = queue.poll(200, TimeUnit.MILLISECONDS);
                if (event == null) {
                    continue;
                }
                String subject = properties.getNats().getTraceSubject();
                natsConnection.publish(subject, objectMapper.writeValueAsBytes(event));
                String stage = event.stage() != null ? event.stage().name() : "UNKNOWN";
                String status = event.status() != null && !event.status().isBlank() ? event.status() : "UNKNOWN";
                meterRegistry.counter("rtp.server.trace.events", "stage", stage, "status", status).increment();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                log.debug("Trace publish failed", e);
            }
        }
    }
}
