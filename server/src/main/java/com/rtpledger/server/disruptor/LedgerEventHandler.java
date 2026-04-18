package com.rtpledger.server.disruptor;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lmax.disruptor.EventHandler;
import com.rtpledger.server.chronicle.ChronicleBalanceEngine;
import com.rtpledger.server.chronicle.ChronicleQueueAppender;
import com.rtpledger.server.chronicle.PostingResult;
import com.rtpledger.server.metrics.ServerMetrics;
import com.rtpledger.server.nats.TransactionNatsReplyBody;
import com.rtpledger.shared.model.LedgerEntry;
import io.nats.client.Connection;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;

@Slf4j
@Component
@RequiredArgsConstructor
public class LedgerEventHandler implements EventHandler<LedgerServerEvent> {

    private final ChronicleBalanceEngine balanceEngine;
    private final ChronicleQueueAppender queueAppender;
    private final Connection natsConnection;
    private final ObjectMapper objectMapper;
    private final ServerMetrics serverMetrics;

    @Override
    public void onEvent(LedgerServerEvent event, long sequence, boolean endOfBatch) {
        try {
            long tBalance = System.nanoTime();
            PostingResult posting = balanceEngine.applyPosting(event.getPayload());
            serverMetrics.recordBalanceComputeNanos(System.nanoTime() - tBalance);

            long tAppend = System.nanoTime();
            long chronicleIndex = queueAppender.appendPostingPayload(posting);
            serverMetrics.recordQueueAppendNanos(System.nanoTime() - tAppend);

            LedgerEntry ledgerEntry = posting.toLedgerEntry(chronicleIndex);

            String replyTo = event.getReplyTo();
            if (replyTo != null && !replyTo.isBlank()) {
                replySuccess(replyTo, ledgerEntry, posting);
            }
        } catch (Exception e) {
            log.error("Ledger event failed correlationId={}",
                    event.getPayload() != null ? event.getPayload().correlationId() : "unknown", e);
            String replyTo = event.getReplyTo();
            if (replyTo != null && !replyTo.isBlank()) {
                replyFailure(replyTo, event.getPayload() != null ? event.getPayload().correlationId() : null, e.getMessage());
            }
        } finally {
            event.clear();
        }
    }

    private void replySuccess(String replyTo, LedgerEntry ledgerEntry, PostingResult posting) {
        try {
            TransactionNatsReplyBody body = new TransactionNatsReplyBody(
                    ledgerEntry.correlationId(),
                    ledgerEntry.entryId().toString(),
                    ledgerEntry.currentBalance().toPlainString(),
                    posting.currency(),
                    ledgerEntry.accountId().toString(),
                    OffsetDateTime.now().toString(),
                    null
            );
            long tReply = System.nanoTime();
            natsConnection.publish(replyTo, objectMapper.writeValueAsBytes(body));
            serverMetrics.recordNatsReplyNanos(System.nanoTime() - tReply);
            log.debug("NATS transaction reply sent correlationId={}", ledgerEntry.correlationId());
        } catch (Exception e) {
            log.warn("NATS reply failed replyTo={}", replyTo, e);
        }
    }

    private void replyFailure(String replyTo, String correlationId, String message) {
        try {
            TransactionNatsReplyBody body = new TransactionNatsReplyBody(
                    correlationId,
                    null,
                    null,
                    null,
                    null,
                    OffsetDateTime.now().toString(),
                    "FAILED"
            );
            long tReply = System.nanoTime();
            try {
                natsConnection.publish(replyTo, objectMapper.writeValueAsBytes(body));
            } finally {
                serverMetrics.recordNatsReplyNanos(System.nanoTime() - tReply);
            }
        } catch (Exception e) {
            log.warn("NATS failure reply could not be sent replyTo={}", replyTo, e);
        }
    }
}
