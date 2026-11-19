package com.ticketresale.event;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import com.ticketresale.event.EventDtos.EventStats;

/**
 * Агрегована статистика події: активні оголошення (кількість, мін./сер./макс. ціна) і продажі (кількість, виручка).
 * <p>
 * Найкоштовніше читання системи (ЛР8): агрегати по listings і purchases, а для purchases немає індексу
 * за event_id — повний перегляд таблиці. Аналітика допускає узгодженість у кінцевому підсумку,
 * тому транзакція readOnly і виконується на репліці (ЛР6).
 */
@Repository
public class EventStatsRepository {

    private static final String SQL = """
            SELECT e.id, e.title, l.active_listings, l.min_price, l.avg_price, l.max_price, p.sold, p.revenue
            FROM events e
            CROSS JOIN LATERAL (
                SELECT count(*) AS active_listings, min(price) AS min_price,
                       round(avg(price), 2) AS avg_price, max(price) AS max_price
                FROM listings WHERE event_id = e.id AND status = 'ACTIVE') l
            CROSS JOIN LATERAL (
                SELECT count(*) AS sold, coalesce(sum(price), 0) AS revenue
                FROM purchases WHERE event_id = e.id) p
            WHERE e.id = ?""";

    private final JdbcTemplate jdbc;
    private final EventStatsProperties properties;

    public EventStatsRepository(JdbcTemplate jdbc, EventStatsProperties properties) {
        this.jdbc = jdbc;
        this.properties = properties;
    }

    /** @return статистика або порожньо, якщо події не існує */
    @Transactional(readOnly = true)
    public Optional<EventStats> load(long eventId) {
        List<EventStats> rows = jdbc.query(SQL, (rs, n) -> new EventStats(
                rs.getLong("id"),
                rs.getString("title"),
                rs.getLong("active_listings"),
                rs.getBigDecimal("min_price"),
                rs.getBigDecimal("avg_price"),
                rs.getBigDecimal("max_price"),
                rs.getLong("sold"),
                rs.getBigDecimal("revenue"),
                Instant.now()), eventId);
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        long costMs = properties.queryCost().toMillis();
        if (costMs > 0) {
            jdbc.queryForObject("SELECT 1 FROM pg_sleep(?)", Integer.class, costMs / 1000.0);
        }
        return Optional.of(rows.get(0));
    }
}
