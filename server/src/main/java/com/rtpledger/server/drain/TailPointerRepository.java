package com.rtpledger.server.drain;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.Optional;

@Repository
@RequiredArgsConstructor
public class TailPointerRepository {

    private final JdbcTemplate jdbcTemplate;

    public Optional<Long> findCommittedIndex(String serverId) {
        var results = jdbcTemplate.query(
                "SELECT chronicle_index FROM tail_pointer WHERE server_id = ?",
                (rs, rowNum) -> rs.getLong("chronicle_index"),
                serverId
        );
        return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
    }

    public void upsertCommittedIndex(Connection connection, String serverId, long chronicleIndex) throws SQLException {
        String sql = """
                INSERT INTO tail_pointer (server_id, chronicle_index, committed_at)
                VALUES (?, ?, now())
                ON CONFLICT (server_id) DO UPDATE SET
                  chronicle_index = excluded.chronicle_index,
                  committed_at = now()
                """;
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, serverId);
            ps.setLong(2, chronicleIndex);
            ps.executeUpdate();
        }
    }
}
