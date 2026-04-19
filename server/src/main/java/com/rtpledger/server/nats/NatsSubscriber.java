package com.rtpledger.server.nats;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lmax.disruptor.RingBuffer;
import com.rtpledger.server.chronicle.ChronicleBalanceEngine;
import com.rtpledger.server.config.RtpServerProperties;
import com.rtpledger.server.disruptor.LedgerServerEvent;
import com.rtpledger.shared.message.FlowTraceEvent;
import com.rtpledger.shared.message.FlowTraceStage;
import com.rtpledger.shared.message.LedgerPostingMessage;
import com.rtpledger.shared.model.BalanceResponse;
import io.nats.client.Connection;
import io.nats.client.Dispatcher;
import io.nats.client.Message;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
public class NatsSubscriber {

    private final Connection connection;
    private final ObjectMapper objectMapper;
    private final RingBuffer<LedgerServerEvent> ledgerRingBuffer;
    private final ChronicleBalanceEngine balanceEngine;
    private final FlowTracePublisher flowTracePublisher;
    private final RtpServerProperties properties;

    @PostConstruct
    public void subscribe() {
        Dispatcher dispatcher = connection.createDispatcher(this::onLedgerMessage);
        dispatcher.subscribe("ledger.>");
        log.info("Subscribed to NATS subject pattern ledger.> (routes balance vs transaction by token count)");
    }

    private void onLedgerMessage(Message msg) {
        String subject = msg.getSubject();
        if (subject.equals(properties.getNats().getTraceSubject())) {
            return;
        }
        String[] parts = subject.split("\\.");
        if (parts.length == 4 && "balance".equals(parts[1])) {
            onBalanceMessage(msg, parts);
        } else if (parts.length == 3) {
            onTransactionMessage(msg);
        } else {
            log.debug("Ignoring unsupported ledger subject: {}", subject);
        }
    }

    private void onBalanceMessage(Message msg, String[] parts) {
        try {
            UUID accountId = UUID.fromString(parts[3]);
            BalanceResponse response = balanceEngine.readBalance(accountId);
            String replyTo = msg.getReplyTo();
            if (replyTo == null || replyTo.isBlank()) {
                log.warn("Balance request missing reply inbox subject={}", msg.getSubject());
                return;
            }
            connection.publish(replyTo, objectMapper.writeValueAsBytes(response));
        } catch (Exception e) {
            log.error("Balance inline handler failed subject={}", msg.getSubject(), e);
        }
    }

    private void onTransactionMessage(Message msg) {
        try {
            LedgerPostingMessage payload = objectMapper.readValue(msg.getData(), LedgerPostingMessage.class);
            flowTracePublisher.publish(new FlowTraceEvent(
                    payload.correlationId(),
                    payload.region(),
                    payload.accountId(),
                    FlowTraceStage.SERVER_NATS_RECEIVED,
                    "RECEIVED",
                    java.time.OffsetDateTime.now().toString(),
                    resolveAmount(payload),
                    resolveCurrency(payload),
                    null,
                    null,
                    null,
                    null,
                    msg.getSubject()
            ));
            boolean published = ledgerRingBuffer.tryPublishEvent((event, sequence) ->
                    event.load(payload, msg.getReplyTo())
            );
            if (!published) {
                flowTracePublisher.publish(new FlowTraceEvent(
                        payload.correlationId(),
                        payload.region(),
                        payload.accountId(),
                        FlowTraceStage.SERVER_RING_REJECTED,
                        "REJECTED",
                        java.time.OffsetDateTime.now().toString(),
                        resolveAmount(payload),
                        resolveCurrency(payload),
                        null,
                        null,
                        null,
                        null,
                        "server_ring_full"
                ));
                log.warn("Server Disruptor ring full; drop correlationId={}", payload.correlationId());
            } else {
                flowTracePublisher.publish(new FlowTraceEvent(
                        payload.correlationId(),
                        payload.region(),
                        payload.accountId(),
                        FlowTraceStage.SERVER_RING_ENQUEUED,
                        "ENQUEUED",
                        java.time.OffsetDateTime.now().toString(),
                        resolveAmount(payload),
                        resolveCurrency(payload),
                        null,
                        null,
                        null,
                        null,
                        null
                ));
            }
        } catch (Exception e) {
            log.error("Failed to enqueue transaction from NATS", e);
        }
    }

    private static String resolveAmount(LedgerPostingMessage payload) {
        if (payload == null || payload.transaction() == null ||
                payload.transaction().creditTransferTransactionInformation() == null ||
                payload.transaction().creditTransferTransactionInformation().instructedAmount() == null) {
            return null;
        }
        return payload.transaction().creditTransferTransactionInformation().instructedAmount().toPlainString();
    }

    private static String resolveCurrency(LedgerPostingMessage payload) {
        if (payload == null || payload.transaction() == null ||
                payload.transaction().creditTransferTransactionInformation() == null) {
            return null;
        }
        return payload.transaction().creditTransferTransactionInformation().instructedCurrency();
    }
}
