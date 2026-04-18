package com.rtpledger.client.controller;

import com.lmax.disruptor.RingBuffer;
import com.rtpledger.client.disruptor.TransactionEvent;
import com.rtpledger.client.metrics.ClientMetrics;
import com.rtpledger.client.nats.BalanceQueryHandler;
import com.rtpledger.client.validation.BianTransactionValidator;
import com.rtpledger.shared.model.BianCreditTransferTransaction;
import com.rtpledger.shared.model.BalanceResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/ledger")
@RequiredArgsConstructor
public class LedgerPostingController {

    private final BianTransactionValidator validator;
    private final RingBuffer<TransactionEvent> ringBuffer;
    private final BalanceQueryHandler balanceQueryHandler;
    private final ClientMetrics clientMetrics;

    @PostMapping("/{region}/{accountId}/post")
    public ResponseEntity<?> post(
            @PathVariable String region,
            @PathVariable String accountId,
            @RequestBody BianCreditTransferTransaction body
    ) {
        List<String> errors = validator.validate(body);
        UUID correlationId = UUID.randomUUID();
        if (!errors.isEmpty()) {
            clientMetrics.incrementTransactionsRejected("validation");
            return ResponseEntity.badRequest().body(new PostingRejectedResponse(
                    correlationId.toString(),
                    "REJECTED",
                    errors
            ));
        }

        var disruptorSample = clientMetrics.startDisruptorPublishSample();
        boolean published = ringBuffer.tryPublishEvent((event, sequence) -> {
            event.setCorrelationId(correlationId.toString());
            event.setRegion(region);
            event.setAccountId(accountId);
            event.setTransaction(body);
        });
        clientMetrics.recordDisruptorPublishLatency(disruptorSample);

        if (!published) {
            clientMetrics.incrementTransactionsRejected("overload");
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(new OverloadedResponse(correlationId.toString(), "OVERLOADED"));
        }

        clientMetrics.incrementTransactionsAccepted(region);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(new PostingAcceptedResponse(
                correlationId.toString(),
                "ACCEPTED",
                accountId,
                region,
                OffsetDateTime.now().toString()
        ));
    }

    @GetMapping("/{region}/{accountId}/balance")
    public BalanceResponse balance(
            @PathVariable String region,
            @PathVariable UUID accountId
    ) {
        long t0 = System.nanoTime();
        try {
            return balanceQueryHandler.query(region, accountId);
        } finally {
            clientMetrics.recordBalanceNanos(System.nanoTime() - t0);
        }
    }

    public record PostingAcceptedResponse(
            String correlationId,
            String status,
            String accountId,
            String region,
            String timestamp
    ) {
    }

    public record PostingRejectedResponse(
            String correlationId,
            String status,
            List<String> errors
    ) {
    }

    public record OverloadedResponse(
            String correlationId,
            String status
    ) {
    }
}
