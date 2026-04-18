package com.rtpledger.server.drain;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.sql.Connection;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;

@Repository
@RequiredArgsConstructor
public class LedgerBalanceRepository {

    public void upsertBatch(Connection connection, List<DrainItem> batch) throws SQLException {
        String sql = """
                INSERT INTO ledger_balance (balance_id, account_id, balance_type, amount, currency, as_of_date, updated_at)
                VALUES (gen_random_uuid(), ?, 'CLBD', ?, ?, ?, now())
                ON CONFLICT (account_id, balance_type) DO UPDATE SET
                  amount = excluded.amount,
                  currency = excluded.currency,
                  as_of_date = excluded.as_of_date,
                  updated_at = now()
                """;
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            for (DrainItem item : batch) {
                var p = item.posting();
                ps.setObject(1, p.accountId());
                ps.setBigDecimal(2, p.currentBalance());
                ps.setString(3, normalizeCurrency(p.currency()));
                ps.setDate(4, Date.valueOf(p.bookingDate()));
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
