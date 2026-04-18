package com.rtpledger.server.chronicle;

import com.rtpledger.shared.model.LedgerEntry;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * In-memory result of balance compute before the Chronicle Queue index is known.
 */
public record PostingResult(
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
        OffsetDateTime createdAt
) {

    public LedgerEntry toLedgerEntry(long chronicleIndex) {
        return new LedgerEntry(
                entryId,
                accountId,
                correlationId,
                endToEndId,
                paymentInfoId,
                debitCreditIndicator,
                amount,
                currency,
                previousBalance,
                currentBalance,
                valueDate,
                bookingDate,
                localInstrument,
                status,
                chronicleIndex,
                createdAt
        );
    }
}
