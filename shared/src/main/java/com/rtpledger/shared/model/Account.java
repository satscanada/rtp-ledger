package com.rtpledger.shared.model;

import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.UUID;

public record Account(
        UUID accountId,
        String accountNumber,
        String accountName,
        String currency,
        String status,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {
    public Account {
        Objects.requireNonNull(accountId, "accountId is required");
        Objects.requireNonNull(accountNumber, "accountNumber is required");
        Objects.requireNonNull(accountName, "accountName is required");
        Objects.requireNonNull(currency, "currency is required");
        Objects.requireNonNull(status, "status is required");
        Objects.requireNonNull(createdAt, "createdAt is required");
        Objects.requireNonNull(updatedAt, "updatedAt is required");
    }
}
