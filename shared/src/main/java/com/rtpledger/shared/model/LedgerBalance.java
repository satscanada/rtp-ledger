package com.rtpledger.shared.model;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.UUID;

public record LedgerBalance(
        UUID balanceId,
        UUID accountId,
        String balanceType,
        BigDecimal amount,
        String currency,
        OffsetDateTime asOfDate,
        OffsetDateTime updatedAt
) {
    public LedgerBalance {
        Objects.requireNonNull(balanceId, "balanceId is required");
        Objects.requireNonNull(accountId, "accountId is required");
        Objects.requireNonNull(balanceType, "balanceType is required");
        Objects.requireNonNull(amount, "amount is required");
        Objects.requireNonNull(currency, "currency is required");
        Objects.requireNonNull(asOfDate, "asOfDate is required");
        Objects.requireNonNull(updatedAt, "updatedAt is required");

        amount = amount.setScale(2, RoundingMode.HALF_EVEN);
    }
}
