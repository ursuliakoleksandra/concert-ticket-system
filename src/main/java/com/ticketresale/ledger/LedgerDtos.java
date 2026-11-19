package com.ticketresale.ledger;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;

public final class LedgerDtos {

    private LedgerDtos() {
    }

    /** Запис операції (для навантажувальних тестів ЛР7 і генератора). */
    public record EntryRequest(
            @NotNull @Positive Long userId,
            @NotNull @Positive Long eventId,
            @NotNull @Pattern(regexp = "BUY|SELL") String type,
            @NotNull @DecimalMin("0.01") BigDecimal amount) {
    }

    public record EntryResponse(UUID id, Long userId, Long eventId, String type, BigDecimal amount,
                                Long purchaseId, Instant createdAt) {
    }

    /** Відповідь Shard-Local запиту: шард, на якому живуть усі дані користувача. */
    public record UserLedger(Long userId, String shard, List<EntryResponse> entries) {
    }

    /** Частковий результат одного шарду в Cross-Shard запиті. */
    public record ShardPart(String shard, long buyCount, BigDecimal buyAmount, long sellCount,
                            BigDecimal sellAmount, long queryMs) {
    }

    /** Агрегований результат Scatter-Gather. */
    public record SalesReport(Long eventId, String mode, long totalMs, long buyCount, BigDecimal buyAmount,
                              long sellCount, BigDecimal sellAmount, List<ShardPart> shards) {
    }

    /** Рядок матриці розподілу даних (Data Distribution Matrix). */
    public record ShardDistribution(String shard, long records, double recordsPercent, long distinctUsers,
                                    long writes, double writesPercent) {
    }

    public record Distribution(String strategy, long totalRecords, long totalWrites,
                               List<ShardDistribution> shards) {
    }
}
