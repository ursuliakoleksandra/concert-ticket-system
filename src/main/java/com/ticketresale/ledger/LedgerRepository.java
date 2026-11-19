package com.ticketresale.ledger;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.stereotype.Repository;

import com.ticketresale.ledger.LedgerDtos.EntryResponse;
import com.ticketresale.ledger.LedgerDtos.ShardPart;
import com.ticketresale.sharding.Shard;

/**
 * SQL-операції над таблицею ledger_entries конкретного шарду. Який шард — вирішує {@code ShardRouter};
 * репозиторій лише виконує запит на переданому вузлі.
 */
@Repository
public class LedgerRepository {

    private static final String INSERT = """
            INSERT INTO ledger_entries (user_id, event_id, entry_type, amount, purchase_id)
            VALUES (?, ?, ?, ?, ?)
            ON CONFLICT (purchase_id, entry_type) WHERE purchase_id IS NOT NULL DO NOTHING
            RETURNING id, user_id, event_id, entry_type, amount, purchase_id, created_at""";

    /** @return створений запис або null, якщо такий крок саги вже виконано раніше (ідемпотентність) */
    public EntryResponse insert(Shard shard, long userId, long eventId, String type, BigDecimal amount,
                                Long purchaseId) {
        List<EntryResponse> rows = shard.jdbc().query(INSERT, LedgerRepository::map,
                userId, eventId, type, amount, purchaseId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public void insertBatch(Shard shard, List<Object[]> rows) {
        shard.jdbc().batchUpdate(
                "INSERT INTO ledger_entries (user_id, event_id, entry_type, amount) VALUES (?, ?, ?, ?)", rows);
    }

    public List<EntryResponse> findByUser(Shard shard, long userId, int limit) {
        return shard.jdbc().query("""
                SELECT id, user_id, event_id, entry_type, amount, purchase_id, created_at
                FROM ledger_entries WHERE user_id = ? ORDER BY created_at DESC LIMIT ?""",
                LedgerRepository::map, userId, limit);
    }

    /** Частина звіту продажів події на одному шарді (Scatter). */
    public ShardPart salesOnShard(Shard shard, long eventId) {
        long started = System.nanoTime();
        long[] counts = new long[2];
        BigDecimal[] sums = {BigDecimal.ZERO, BigDecimal.ZERO};
        shard.jdbc().query("""
                SELECT entry_type, count(*) AS cnt, coalesce(sum(amount), 0) AS total
                FROM ledger_entries WHERE event_id = ? GROUP BY entry_type""",
                (RowCallbackHandler) rs -> {
                    int i = "BUY".equals(rs.getString("entry_type")) ? 0 : 1;
                    counts[i] = rs.getLong("cnt");
                    sums[i] = rs.getBigDecimal("total");
                }, eventId);
        long ms = (System.nanoTime() - started) / 1_000_000;
        return new ShardPart(shard.name(), counts[0], sums[0], counts[1], sums[1], ms);
    }

    public long count(Shard shard) {
        Long n = shard.jdbc().queryForObject("SELECT count(*) FROM ledger_entries", Long.class);
        return n == null ? 0 : n;
    }

    public long distinctUsers(Shard shard) {
        Long n = shard.jdbc().queryForObject("SELECT count(DISTINCT user_id) FROM ledger_entries", Long.class);
        return n == null ? 0 : n;
    }

    public void truncate(Shard shard) {
        shard.jdbc().execute("TRUNCATE ledger_entries");
    }

    private static EntryResponse map(ResultSet rs, int rowNum) throws SQLException {
        Timestamp created = rs.getTimestamp("created_at");
        return new EntryResponse(
                rs.getObject("id", UUID.class),
                rs.getLong("user_id"),
                rs.getLong("event_id"),
                rs.getString("entry_type"),
                rs.getBigDecimal("amount"),
                rs.getObject("purchase_id", Long.class),
                created == null ? null : created.toInstant());
    }
}
