package com.rtpledger.shared.model;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;
import java.util.UUID;

public record NatsReply(
        String correlationId,
        UUID ledgerEntryId,
        BigDecimal currentBalance
) {
    public NatsReply {
        Objects.requireNonNull(correlationId, "correlationId is required");
        Objects.requireNonNull(ledgerEntryId, "ledgerEntryId is required");
        Objects.requireNonNull(currentBalance, "currentBalance is required");

        currentBalance = currentBalance.setScale(2, RoundingMode.HALF_EVEN);
    }
}
