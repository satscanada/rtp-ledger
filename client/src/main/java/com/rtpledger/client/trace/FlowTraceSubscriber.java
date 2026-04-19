package com.rtpledger.client.trace;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rtpledger.client.config.RtpClientProperties;
import com.rtpledger.shared.message.FlowTraceEvent;
import io.nats.client.Connection;
import io.nats.client.Dispatcher;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class FlowTraceSubscriber {

    private final Connection natsConnection;
    private final ObjectMapper objectMapper;
    private final RtpClientProperties properties;
    private final FlowTraceStore flowTraceStore;

    @PostConstruct
    public void subscribe() {
        Dispatcher dispatcher = natsConnection.createDispatcher(msg -> {
            try {
                FlowTraceEvent event = objectMapper.readValue(msg.getData(), FlowTraceEvent.class);
                flowTraceStore.ingest(event);
            } catch (Exception e) {
                log.debug("Skipping malformed trace event from NATS", e);
            }
        });
        dispatcher.subscribe(properties.getNats().getTraceSubject());
        log.info("Subscribed to trace subject {}", properties.getNats().getTraceSubject());
    }
}
