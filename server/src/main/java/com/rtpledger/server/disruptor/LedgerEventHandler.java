package com.rtpledger.server.disruptor;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lmax.disruptor.EventHandler;
import com.rtpledger.server.chronicle.ChronicleBalanceEngine;
import com.rtpledger.server.chronicle.ChronicleQueueAppender;
import com.rtpledger.server.chronicle.PostingResult;
import com.rtpledger.server.metrics.ServerMetrics;
import com.rtpledger.server.nats.FlowTracePublisher;
import com.rtpledger.server.nats.TransactionNatsReplyBody;
import com.rtpledger.shared.message.FlowTraceEvent;
import com.rtpledger.shared.message.FlowTraceStage;
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
    private final FlowTracePublisher flowTracePublisher;

    @Override
    public void onEvent(LedgerServerEvent event, long sequence, boolean endOfBatch) {
        PostingResult posting = null;
        FlowTraceStage failureStage = FlowTraceStage.SERVER_BALANCE_COMPUTE_FAILED;
        try {
            long tBalance = System.nanoTime();
            posting = balanceEngine.applyPosting(event.getPayload());
            serverMetrics.recordBalanceComputeNanos(System.nanoTime() - tBalance);
            flowTracePublisher.publish(new FlowTraceEvent(
                    posting.correlationId(),
                    event.getPayload() != null ? event.getPayload().region() : null,
                    posting.accountId().toString(),
                    FlowTraceStage.SERVER_BALANCE_COMPUTE_OK,
                    posting.status(),
                    OffsetDateTime.now().toString(),
                    posting.amount().toPlainString(),
                    posting.currency(),
                    posting.debitCreditIndicator(),
                    posting.previousBalance().toPlainString(),
                    posting.currentBalance().toPlainString(),
                    null,
                    null
            ));

            failureStage = FlowTraceStage.SERVER_QUEUE_APPEND_FAILED;
            long tAppend = System.nanoTime();
            long chronicleIndex = queueAppender.appendPostingPayload(posting);
            serverMetrics.recordQueueAppendNanos(System.nanoTime() - tAppend);
            flowTracePublisher.publish(new FlowTraceEvent(
                    posting.correlationId(),
                    event.getPayload() != null ? event.getPayload().region() : null,
                    posting.accountId().toString(),
                    FlowTraceStage.SERVER_QUEUE_APPEND_OK,
                    posting.status(),
                    OffsetDateTime.now().toString(),
                    posting.amount().toPlainString(),
                    posting.currency(),
                    posting.debitCreditIndicator(),
                    posting.previousBalance().toPlainString(),
                    posting.currentBalance().toPlainString(),
                    chronicleIndex,
                    null
            ));

            LedgerEntry ledgerEntry = posting.toLedgerEntry(chronicleIndex);

            String replyTo = event.getReplyTo();
            if (replyTo != null && !replyTo.isBlank()) {
                replySuccess(replyTo, ledgerEntry, posting);
            }
        } catch (Exception e) {
            log.error("Ledger event failed correlationId={}",
                    event.getPayload() != null ? event.getPayload().correlationId() : "unknown", e);
            flowTracePublisher.publish(new FlowTraceEvent(
                    event.getPayload() != null ? event.getPayload().correlationId() : null,
                    event.getPayload() != null ? event.getPayload().region() : null,
                    event.getPayload() != null ? event.getPayload().accountId() : null,
                    failureStage,
                    "FAILED",
                    OffsetDateTime.now().toString(),
                    posting != null ? posting.amount().toPlainString() : null,
                    posting != null ? posting.currency() : null,
                    posting != null ? posting.debitCreditIndicator() : null,
                    posting != null ? posting.previousBalance().toPlainString() : null,
                    posting != null ? posting.currentBalance().toPlainString() : null,
                    null,
                    e.getMessage()
            ));
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
            flowTracePublisher.publish(new FlowTraceEvent(
                    posting.correlationId(),
                    null,
                    posting.accountId().toString(),
                    FlowTraceStage.SERVER_NATS_REPLY_OK,
                    posting.status(),
                    OffsetDateTime.now().toString(),
                    posting.amount().toPlainString(),
                    posting.currency(),
                    posting.debitCreditIndicator(),
                    posting.previousBalance().toPlainString(),
                    posting.currentBalance().toPlainString(),
                    ledgerEntry.chronicleIndex(),
                    replyTo
            ));
            log.debug("NATS transaction reply sent correlationId={}", ledgerEntry.correlationId());
        } catch (Exception e) {
            flowTracePublisher.publish(new FlowTraceEvent(
                    posting.correlationId(),
                    null,
                    posting.accountId().toString(),
                    FlowTraceStage.SERVER_NATS_REPLY_FAILED,
                    "FAILED",
                    OffsetDateTime.now().toString(),
                    posting.amount().toPlainString(),
                    posting.currency(),
                    posting.debitCreditIndicator(),
                    posting.previousBalance().toPlainString(),
                    posting.currentBalance().toPlainString(),
                    ledgerEntry.chronicleIndex(),
                    e.getMessage()
            ));
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
            flowTracePublisher.publish(new FlowTraceEvent(
                    correlationId,
                    null,
                    null,
                    FlowTraceStage.SERVER_NATS_REPLY_OK,
                    "FAILED",
                    OffsetDateTime.now().toString(),
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    message
            ));
        } catch (Exception e) {
            log.warn("NATS failure reply could not be sent replyTo={}", replyTo, e);
        }
    }
}
