package com.rtpledger.shared.message;

import com.rtpledger.shared.model.BianCreditTransferTransaction;

/**
 * NATS payload for {@code ledger.{region}.{accountId}} (client publish → server ingest).
 */
public record LedgerPostingMessage(
        String correlationId,
        BianCreditTransferTransaction transaction,
        String region,
        String accountId
) {
}
