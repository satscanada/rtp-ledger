package com.rtpledger.server.nats;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lmax.disruptor.RingBuffer;
import com.rtpledger.server.chronicle.ChronicleBalanceEngine;
import com.rtpledger.server.disruptor.LedgerServerEvent;
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

    @PostConstruct
    public void subscribe() {
        Dispatcher dispatcher = connection.createDispatcher(this::onLedgerMessage);
        dispatcher.subscribe("ledger.>");
        log.info("Subscribed to NATS subject pattern ledger.> (routes balance vs transaction by token count)");
    }

    private void onLedgerMessage(Message msg) {
        String subject = msg.getSubject();
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
            boolean published = ledgerRingBuffer.tryPublishEvent((event, sequence) ->
                    event.load(payload, msg.getReplyTo())
            );
            if (!published) {
                log.warn("Server Disruptor ring full; drop correlationId={}", payload.correlationId());
            }
        } catch (Exception e) {
            log.error("Failed to enqueue transaction from NATS", e);
        }
    }
}
