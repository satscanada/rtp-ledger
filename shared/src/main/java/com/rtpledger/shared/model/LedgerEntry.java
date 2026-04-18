package com.rtpledger.shared.model;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.UUID;

public record LedgerEntry(
        UUID entryId,
        UUID accountId,
        String correlationId,
        String endToEndId,
        String paymentInfoId,
        String debitCreditIndicator,
        BigDecimal amount,
        String currency,
        BigDecimal previousBalance,
        BigDecimal currentBalance,
        LocalDate valueDate,
        LocalDate bookingDate,
        String localInstrument,
        String status,
        long chronicleIndex,
        OffsetDateTime createdAt
) {
    public LedgerEntry {
        Objects.requireNonNull(entryId, "entryId is required");
        Objects.requireNonNull(accountId, "accountId is required");
        Objects.requireNonNull(correlationId, "correlationId is required");
        Objects.requireNonNull(debitCreditIndicator, "debitCreditIndicator is required");
        Objects.requireNonNull(amount, "amount is required");
        Objects.requireNonNull(currency, "currency is required");
        Objects.requireNonNull(previousBalance, "previousBalance is required");
        Objects.requireNonNull(currentBalance, "currentBalance is required");
        Objects.requireNonNull(status, "status is required");
        Objects.requireNonNull(createdAt, "createdAt is required");

        amount = amount.setScale(2, RoundingMode.HALF_EVEN);
        previousBalance = previousBalance.setScale(2, RoundingMode.HALF_EVEN);
        currentBalance = currentBalance.setScale(2, RoundingMode.HALF_EVEN);
    }
}
