package com.rtpledger.server.drain;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.sql.Connection;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import java.util.UUID;

@Repository
@RequiredArgsConstructor
public class LedgerEntryRepository {

    public void insertBatch(Connection connection, List<DrainItem> batch) throws SQLException {
        String sql = """
                INSERT INTO ledger_entry (
                    entry_id, account_id, correlation_id, end_to_end_id, payment_info_id,
                    debit_credit_indicator, amount, currency, previous_balance, current_balance,
                    value_date, booking_date, local_instrument, status, chronicle_index, created_at
                ) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """;
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            for (DrainItem item : batch) {
                var p = item.posting();
                ps.setObject(1, p.entryId());
                ps.setObject(2, p.accountId());
                ps.setObject(3, UUID.fromString(p.correlationId()));
                ps.setString(4, p.endToEndId());
                ps.setString(5, p.paymentInfoId());
                ps.setString(6, p.debitCreditIndicator());
                ps.setBigDecimal(7, p.amount());
                ps.setString(8, normalizeCurrency(p.currency()));
                ps.setBigDecimal(9, p.previousBalance());
                ps.setBigDecimal(10, p.currentBalance());
                ps.setDate(11, Date.valueOf(p.valueDate()));
                ps.setDate(12, Date.valueOf(p.bookingDate()));
                ps.setString(13, p.localInstrument());
                ps.setString(14, p.status());
                ps.setLong(15, item.chronicleIndex());
                ps.setTimestamp(16, Timestamp.from(p.createdAt().toInstant()));
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    private static String normalizeCurrency(String currency) {
        String c = currency.trim();
        if (c.length() != 3) {
            throw new IllegalArgumentException("currency must be ISO 4217 length 3");
        }
        return c;
    }
}
