package com.rtpledger.client.nats;

import com.rtpledger.shared.model.BianCreditTransferTransaction;

public record NatsPublishPayload(
        String correlationId,
        BianCreditTransferTransaction transaction,
        String region,
        String accountId
) {
}
