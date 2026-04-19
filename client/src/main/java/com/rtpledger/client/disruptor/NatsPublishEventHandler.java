package com.rtpledger.client.disruptor;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lmax.disruptor.EventHandler;
import com.rtpledger.client.config.RtpClientProperties;
import com.rtpledger.client.metrics.ClientMetrics;
import com.rtpledger.client.nats.FlowTracePublisher;
import com.rtpledger.shared.message.FlowTraceEvent;
import com.rtpledger.shared.message.FlowTraceStage;
import com.rtpledger.shared.message.LedgerPostingMessage;
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
    private final ClientMetrics clientMetrics;
    private final FlowTracePublisher flowTracePublisher;

    @Override
    public void onEvent(TransactionEvent event, long sequence, boolean endOfBatch) {
        try {
            String prefix = properties.getNats().getSubjectPrefix();
            String subject = prefix + "." + event.getRegion() + "." + event.getAccountId();
            LedgerPostingMessage payload = new LedgerPostingMessage(
                    event.getCorrelationId(),
                    event.getTransaction(),
                    event.getRegion(),
                    event.getAccountId()
            );
            byte[] data = objectMapper.writeValueAsBytes(payload);
            byte[] payloadBytes = data;
            clientMetrics.recordNatsPublishLatency(() -> natsConnection.publish(subject, payloadBytes));
            flowTracePublisher.publish(new FlowTraceEvent(
                    event.getCorrelationId(),
                    event.getRegion(),
                    event.getAccountId(),
                    FlowTraceStage.CLIENT_NATS_PUBLISH_OK,
                    "PUBLISHED",
                    java.time.OffsetDateTime.now().toString(),
                    resolveAmount(event),
                    resolveCurrency(event),
                    null,
                    null,
                    null,
                    null,
                    subject
            ));
        } catch (Exception e) {
            clientMetrics.incrementNatsPublishFailures();
            flowTracePublisher.publish(new FlowTraceEvent(
                    event.getCorrelationId(),
                    event.getRegion(),
                    event.getAccountId(),
                    FlowTraceStage.CLIENT_NATS_PUBLISH_FAILED,
                    "FAILED",
                    java.time.OffsetDateTime.now().toString(),
                    resolveAmount(event),
                    resolveCurrency(event),
                    null,
                    null,
                    null,
                    null,
                    e.getMessage()
            ));
            log.error("NATS publish failed correlationId={}", event.getCorrelationId(), e);
        } finally {
            event.clear();
        }
    }

    private static String resolveAmount(TransactionEvent event) {
        if (event.getTransaction() == null ||
                event.getTransaction().creditTransferTransactionInformation() == null ||
                event.getTransaction().creditTransferTransactionInformation().instructedAmount() == null) {
            return null;
        }
        return event.getTransaction().creditTransferTransactionInformation().instructedAmount().toPlainString();
    }

    private static String resolveCurrency(TransactionEvent event) {
        if (event.getTransaction() == null || event.getTransaction().creditTransferTransactionInformation() == null) {
            return null;
        }
        return event.getTransaction().creditTransferTransactionInformation().instructedCurrency();
    }
}
