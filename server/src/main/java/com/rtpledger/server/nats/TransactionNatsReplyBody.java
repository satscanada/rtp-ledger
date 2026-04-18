package com.rtpledger.server.nats;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * NATS reply body for transaction path (when {@code replyTo} is present).
 * Matches server.instructions.md shape.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record TransactionNatsReplyBody(
        String correlationId,
        String ledgerEntryId,
        String currentBalance,
        String currency,
        String accountId,
        String timestamp,
        String status
) {
}
