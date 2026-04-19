package com.rtpledger.shared.message;

/**
 * Metadata-only event for transaction lifecycle visualization.
 */
public record FlowTraceEvent(
        String correlationId,
        String region,
        String accountId,
        FlowTraceStage stage,
        String status,
        String timestamp,
        String amount,
        String currency,
        String debitCreditIndicator,
        String previousBalance,
        String currentBalance,
        Long chronicleIndex,
        String detail
) {
}
