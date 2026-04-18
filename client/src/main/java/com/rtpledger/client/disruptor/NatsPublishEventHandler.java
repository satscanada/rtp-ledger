package com.rtpledger.client.disruptor;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lmax.disruptor.EventHandler;
import com.rtpledger.client.config.RtpClientProperties;
import com.rtpledger.client.nats.NatsPublishPayload;
import io.nats.client.Connection;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class NatsPublishEventHandler implements EventHandler<TransactionEvent> {

    private final ObjectMapper objectMapper;
    private final Connection natsConnection;
    private final RtpClientProperties properties;

    @Override
    public void onEvent(TransactionEvent event, long sequence, boolean endOfBatch) {
        try {
            String prefix = properties.getNats().getSubjectPrefix();
            String subject = prefix + "." + event.getRegion() + "." + event.getAccountId();
            NatsPublishPayload payload = new NatsPublishPayload(
                    event.getCorrelationId(),
                    event.getTransaction(),
                    event.getRegion(),
                    event.getAccountId()
            );
            byte[] data = objectMapper.writeValueAsBytes(payload);
            natsConnection.publish(subject, data);
        } catch (Exception e) {
            log.error("NATS publish failed correlationId={}", event.getCorrelationId(), e);
        } finally {
            event.clear();
        }
    }
}
