package com.rtpledger.server.nats;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rtpledger.server.config.RtpServerProperties;
import com.rtpledger.shared.message.FlowTraceEvent;
import io.micrometer.core.instrument.MeterRegistry;
import io.nats.client.Connection;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class FlowTracePublisher {

    private final Connection natsConnection;
    private final ObjectMapper objectMapper;
    private final RtpServerProperties properties;
    private final MeterRegistry meterRegistry;

    public void publish(FlowTraceEvent event) {
        if (event == null) {
            return;
        }
        try {
            String subject = properties.getNats().getTraceSubject();
            natsConnection.publish(subject, objectMapper.writeValueAsBytes(event));
            String stage = event.stage() != null ? event.stage().name() : "UNKNOWN";
            String status = event.status() != null && !event.status().isBlank() ? event.status() : "UNKNOWN";
            meterRegistry.counter("rtp.server.trace.events", "stage", stage, "status", status).increment();
        } catch (Exception e) {
            log.debug("Trace publish skipped correlationId={} stage={}",
                    event.correlationId(), event.stage(), e);
        }
    }
}
