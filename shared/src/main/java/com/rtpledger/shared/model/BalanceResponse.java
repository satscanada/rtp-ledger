package com.rtpledger.shared.model;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.UUID;

public record BalanceResponse(
        UUID accountId,
        BigDecimal balance,
        String currency,
        OffsetDateTime asOf
) {
    public BalanceResponse {
        Objects.requireNonNull(accountId, "accountId is required");
        Objects.requireNonNull(balance, "balance is required");
        Objects.requireNonNull(currency, "currency is required");
        Objects.requireNonNull(asOf, "asOf is required");

        balance = balance.setScale(2, RoundingMode.HALF_EVEN);
    }
}
