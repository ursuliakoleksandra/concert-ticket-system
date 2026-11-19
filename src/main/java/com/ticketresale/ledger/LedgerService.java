package com.ticketresale.ledger;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import com.ticketresale.common.exception.BusinessRuleException;
import com.ticketresale.ledger.LedgerDtos.Distribution;
import com.ticketresale.ledger.LedgerDtos.EntryResponse;
import com.ticketresale.ledger.LedgerDtos.SalesReport;
import com.ticketresale.ledger.LedgerDtos.ShardDistribution;
import com.ticketresale.ledger.LedgerDtos.ShardPart;
import com.ticketresale.ledger.LedgerDtos.UserLedger;
import com.ticketresale.purchase.Purchase;
import com.ticketresale.sharding.Shard;
import com.ticketresale.sharding.ShardRouter;

import jakarta.annotation.PreDestroy;

/**
 * Журнал операцій користувачів, розподілений між шардами за ключем {@code user_id} (ЛР7).
 * <ul>
 *   <li><b>Shard-Local:</b> запис і історія операцій користувача — один шард, одна локальна транзакція;</li>
 *   <li><b>Cross-Shard (Scatter-Gather):</b> звіт продажів події — запит до всіх шардів і злиття результатів;</li>
 *   <li><b>Cross-Shard Write:</b> купівля створює BUY для покупця і SELL для продавця, які можуть лежати
 *       на різних шардах — дві незалежні локальні транзакції (крок саги), кожна ідемпотентна.</li>
 * </ul>
 */
@Service
public class LedgerService {

    private static final Logger log = LoggerFactory.getLogger(LedgerService.class);
    private static final String WRITES_KEY = "resale:shard:writes:";
    private static final int BATCH = 1000;

    private final ShardRouter router;
    private final LedgerRepository repository;
    private final StringRedisTemplate redis;
    /** Віртуальні потоки (Java 21): паралельні запити до шардів без окремого пулу потоків. */
    private final ExecutorService fanOut = Executors.newVirtualThreadPerTaskExecutor();

    public LedgerService(ShardRouter router, LedgerRepository repository, StringRedisTemplate redis) {
        this.router = router;
        this.repository = repository;
        this.redis = redis;
    }

    /** Shard-Local запис: рядок потрапляє на шард власника. */
    public EntryResponse record(long userId, long eventId, String type, BigDecimal amount) {
        Shard shard = router.route(userId);
        EntryResponse saved = repository.insert(shard, userId, eventId, type, amount, null);
        countWrites(shard, 1);
        return saved;
    }

    /** Shard-Local читання: усі операції користувача на одному шарді, запит до інших не потрібен. */
    public UserLedger history(long userId, int limit) {
        Shard shard = router.route(userId);
        return new UserLedger(userId, shard.name(), repository.findByUser(shard, userId, limit));
    }

    public String shardOf(long userId) {
        return router.route(userId).name();
    }

    /**
     * Cross-Shard запит (Scatter-Gather): кожен шард рахує свою частину, сервіс зливає результати.
     *
     * @param parallel true — запити до шардів одночасно (час ≈ найповільніший шард),
     *                 false — по черзі (час ≈ сума)
     */
    public SalesReport salesReport(long eventId, boolean parallel) {
        long started = System.nanoTime();
        List<ShardPart> parts;
        if (parallel) {
            List<CompletableFuture<ShardPart>> futures = router.all().stream()
                    .map(shard -> CompletableFuture.supplyAsync(() -> repository.salesOnShard(shard, eventId), fanOut))
                    .toList();
            parts = futures.stream().map(CompletableFuture::join).toList();
        } else {
            parts = router.all().stream().map(shard -> repository.salesOnShard(shard, eventId)).toList();
        }
        long buyCount = 0;
        long sellCount = 0;
        BigDecimal buy = BigDecimal.ZERO;
        BigDecimal sell = BigDecimal.ZERO;
        for (ShardPart part : parts) {
            buyCount += part.buyCount();
            sellCount += part.sellCount();
            buy = buy.add(part.buyAmount());
            sell = sell.add(part.sellAmount());
        }
        long totalMs = (System.nanoTime() - started) / 1_000_000;
        return new SalesReport(eventId, parallel ? "parallel" : "sequential", totalMs,
                buyCount, buy, sellCount, sell, parts);
    }

    /**
     * Cross-Shard Write після купівлі: крок 1 — BUY на шарді покупця, крок 2 — SELL на шарді продавця.
     * Локальної ACID-транзакції на два шарди не існує, тому кожен крок — окрема транзакція свого шарду,
     * а повтор кроку безпечний (унікальний індекс purchase_id + entry_type). Джерело істини — таблиця
     * purchases у primary: пропущений крок можна відновити повторним викликом (компенсація не потрібна).
     */
    public void recordPurchase(Purchase purchase) {
        step(purchase.getBuyerId(), purchase, "BUY");
        step(purchase.getSellerId(), purchase, "SELL");
    }

    private void step(long userId, Purchase purchase, String type) {
        Shard shard = router.route(userId);
        try {
            if (repository.insert(shard, userId, purchase.getEventId(), type, purchase.getPrice(),
                    purchase.getId()) != null) {
                countWrites(shard, 1);
            }
        } catch (DataAccessException e) {
            log.warn("Крок саги {} для покупки {} на {} не виконано: {}", type, purchase.getId(), shard.name(),
                    e.getMessage());
        }
    }

    /**
     * Генерація тестового набору даних (Dataset Distribution Baseline). Попередні дані шардів видаляються.
     *
     * @param pattern uniform — user_id рівномірно 1..users;
     *                even — лише парні user_id (ідентифікатори з кроком 2, низька ентропія молодшого біта);
     *                whale — половина записів належить одному користувачу (великий тенант)
     */
    public Distribution generate(int count, int users, int events, String pattern) {
        if (count < 1 || count > 1_000_000 || users < 1 || events < 1) {
            throw new BusinessRuleException("Допустимо: 1 ≤ count ≤ 1 000 000, users ≥ 1, events ≥ 1");
        }
        if (!List.of("uniform", "even", "whale").contains(pattern)) {
            throw new BusinessRuleException("pattern: uniform | even | whale");
        }
        router.all().forEach(repository::truncate);
        resetWriteCounters();

        Map<Integer, List<Object[]>> buffers = new HashMap<>();
        ThreadLocalRandom random = ThreadLocalRandom.current();
        for (int i = 0; i < count; i++) {
            long userId = switch (pattern) {
                case "even" -> 2L * random.nextLong(1, users + 1L);
                case "whale" -> random.nextBoolean() ? 1L : random.nextLong(2, users + 2L);
                default -> random.nextLong(1, users + 1L);
            };
            Shard shard = router.route(userId);
            List<Object[]> buffer = buffers.computeIfAbsent(shard.index(), k -> new ArrayList<>());
            buffer.add(new Object[] {userId, random.nextLong(1, events + 1L), random.nextBoolean() ? "BUY" : "SELL",
                    BigDecimal.valueOf(random.nextLong(10_000, 150_000), 2)});
            if (buffer.size() >= BATCH) {
                flush(shard, buffer);
            }
        }
        buffers.forEach((index, buffer) -> flush(router.all().get(index), buffer));
        return distribution();
    }

    private void flush(Shard shard, List<Object[]> buffer) {
        if (!buffer.isEmpty()) {
            repository.insertBatch(shard, buffer);
            countWrites(shard, buffer.size());
            buffer.clear();
        }
    }

    /** Матриця розподілу: кількість і частка записів, кількість різних користувачів, частка операцій запису. */
    public Distribution distribution() {
        List<Shard> shards = router.all();
        long[] records = new long[shards.size()];
        long[] users = new long[shards.size()];
        long[] writes = new long[shards.size()];
        long totalRecords = 0;
        long totalWrites = 0;
        for (Shard shard : shards) {
            records[shard.index()] = repository.count(shard);
            users[shard.index()] = repository.distinctUsers(shard);
            writes[shard.index()] = readWrites(shard);
            totalRecords += records[shard.index()];
            totalWrites += writes[shard.index()];
        }
        List<ShardDistribution> rows = new ArrayList<>();
        for (Shard shard : shards) {
            int i = shard.index();
            rows.add(new ShardDistribution(shard.name(), records[i], percent(records[i], totalRecords), users[i],
                    writes[i], percent(writes[i], totalWrites)));
        }
        return new Distribution(router.strategy().description(), totalRecords, totalWrites, rows);
    }

    public void resetWriteCounters() {
        for (Shard shard : router.all()) {
            try {
                redis.delete(WRITES_KEY + shard.name());
            } catch (DataAccessException e) {
                log.warn("Лічильник записів {} не скинуто: {}", shard.name(), e.getMessage());
            }
        }
    }

    private void countWrites(Shard shard, long n) {
        try {
            redis.opsForValue().increment(WRITES_KEY + shard.name(), n);
        } catch (DataAccessException e) {
            // Лічильник лише для звіту про розподіл; запис у шард уже виконано
        }
    }

    private long readWrites(Shard shard) {
        try {
            String value = redis.opsForValue().get(WRITES_KEY + shard.name());
            return value == null ? 0 : Long.parseLong(value);
        } catch (DataAccessException e) {
            return 0;
        }
    }

    private static double percent(long part, long total) {
        return total == 0 ? 0 : BigDecimal.valueOf(100.0 * part / total).setScale(2, RoundingMode.HALF_UP).doubleValue();
    }

    @PreDestroy
    void shutdown() {
        fanOut.shutdown();
    }
}
