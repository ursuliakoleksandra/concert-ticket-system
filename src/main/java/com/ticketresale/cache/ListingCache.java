package com.ticketresale.cache;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ticketresale.listing.ListingResponse;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * Розподілений кеш активних оголошень події (Cache-Aside / Lazy Loading) у Redis.
 *
 * <h2>Схема ключів</h2>
 * <pre>
 *   resale:listings:event:{eventId}:ver                                  — версія кешу події (лічильник)
 *   resale:listings:event:{eventId}:v{ver}:page={p}:size={s}:sort={sort}:max={maxPrice}
 * </pre>
 * Усі параметри запиту, що впливають на відповідь, входять у ключ явно — колізії неможливі.
 *
 * <h2>Інвалідація</h2>
 * Будь-яка мутація оголошень події (створення, скасування, продаж) збільшує версію події
 * ({@code INCR}). Наступне читання формує ключ з новою версією → MISS → свіжі дані з БД.
 * Так одним атомарним O(1)-викликом інвалідуються одразу всі варіанти сторінок і фільтрів,
 * а застарілі записи старої версії просто доживають свій TTL.
 *
 * <h2>Відмовостійкість</h2>
 * Будь-яка помилка Redis не ламає запит: дані читаються з БД напряму (статус BYPASS).
 */
@Component
public class ListingCache {

    private static final Logger log = LoggerFactory.getLogger(ListingCache.class);
    private static final String PREFIX = "resale:listings:event:";

    private final StringRedisTemplate redis;
    private final ObjectMapper mapper;
    private final CacheProperties properties;
    private final JavaType pageType;
    private final Counter hits;
    private final Counter misses;
    private final Counter bypasses;
    private final Counter disabled;
    private final Counter invalidations;

    public ListingCache(StringRedisTemplate redis, ObjectMapper mapper, CacheProperties properties,
                        MeterRegistry meters) {
        this.redis = redis;
        this.mapper = mapper;
        this.properties = properties;
        this.pageType = mapper.getTypeFactory().constructParametricType(CachedPage.class, ListingResponse.class);
        this.hits = meters.counter("resale.cache.requests", "result", "hit");
        this.misses = meters.counter("resale.cache.requests", "result", "miss");
        this.bypasses = meters.counter("resale.cache.requests", "result", "bypass");
        this.disabled = meters.counter("resale.cache.requests", "result", "disabled");
        this.invalidations = meters.counter("resale.cache.invalidations");
    }

    /**
     * Cache-Aside: 1) пошук за ключем; 2) HIT — повернення з Redis без БД;
     * 3) MISS — читання з БД через {@code loader}, запис у Redis з TTL, повернення.
     */
    public CacheResult<Page<ListingResponse>> getOrLoad(Long eventId, BigDecimal maxPrice, Pageable pageable,
                                                        Supplier<Page<ListingResponse>> loader) {
        if (!properties.enabled()) {
            disabled.increment();
            return new CacheResult<>(loader.get(), CacheStatus.DISABLED);
        }

        String key;
        try {
            key = pageKey(eventId, currentVersion(eventId), maxPrice, pageable);
            String cached = redis.opsForValue().get(key);
            if (cached != null) {
                CachedPage<ListingResponse> page = mapper.readValue(cached, pageType);
                hits.increment();
                log.debug("Cache HIT {}", key);
                return new CacheResult<>(page.toPage(pageable), CacheStatus.HIT);
            }
        } catch (DataAccessException | JsonProcessingException e) {
            // Redis недоступний або запис пошкоджено — працюємо з БД напряму
            log.warn("Кеш недоступний, читання з БД напряму: {}", e.getMessage());
            bypasses.increment();
            return new CacheResult<>(loader.get(), CacheStatus.BYPASS);
        }

        Page<ListingResponse> fresh = loader.get();
        try {
            redis.opsForValue().set(key, mapper.writeValueAsString(CachedPage.of(fresh)), ttlWithJitter());
        } catch (DataAccessException | JsonProcessingException e) {
            log.warn("Не вдалося записати в кеш {}: {}", key, e.getMessage());
        }
        misses.increment();
        log.debug("Cache MISS {}", key);
        return new CacheResult<>(fresh, CacheStatus.MISS);
    }

    /**
     * Інвалідація після успішного COMMIT: читач, що прийде після відповіді на мутацію,
     * гарантовано побачить нову версію і свіжі дані з БД.
     */
    public void invalidateAfterCommit(Long eventId) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    invalidate(eventId);
                }
            });
        } else {
            invalidate(eventId);
        }
    }

    void invalidate(Long eventId) {
        try {
            redis.opsForValue().increment(versionKey(eventId));
            invalidations.increment();
            log.debug("Cache INVALIDATE event {}", eventId);
        } catch (DataAccessException e) {
            // Redis недоступний: застарілий запис (якщо є) зникне не пізніше ніж через TTL
            log.warn("Не вдалося інвалідувати кеш події {}: {}", eventId, e.getMessage());
        }
    }

    private long currentVersion(Long eventId) {
        String value = redis.opsForValue().get(versionKey(eventId));
        return value == null ? 0 : Long.parseLong(value);
    }

    private static String versionKey(Long eventId) {
        return PREFIX + eventId + ":ver";
    }

    static String pageKey(Long eventId, long version, BigDecimal maxPrice, Pageable pageable) {
        String sort = pageable.getSort().isSorted()
                ? pageable.getSort().stream()
                        .map(order -> order.getProperty() + "." + order.getDirection())
                        .collect(Collectors.joining(","))
                : "none";
        String max = maxPrice == null ? "any" : maxPrice.stripTrailingZeros().toPlainString();
        return PREFIX + eventId + ":v" + version
                + ":page=" + pageable.getPageNumber()
                + ":size=" + pageable.getPageSize()
                + ":sort=" + sort
                + ":max=" + max;
    }

    /** TTL ± jitter: записи, створені одночасно, не зникають одночасно (Cache Avalanche). */
    private Duration ttlWithJitter() {
        long base = properties.listingsTtl().toMillis();
        double factor = 1 + ThreadLocalRandom.current().nextDouble(-properties.ttlJitter(), properties.ttlJitter());
        return Duration.ofMillis(Math.max(1000, Math.round(base * factor)));
    }
}
