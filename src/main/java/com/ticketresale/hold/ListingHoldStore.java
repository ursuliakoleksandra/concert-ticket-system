package com.ticketresale.hold;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

/**
 * Зберігання бронювань оголошень у Redis (спільне сховище для всіх екземплярів).
 * <p>
 * Ключ: {@code hold:listing:{listingId}}, значення: id покупця, TTL — тривалість бронювання.
 * Термін дії відраховує сам Redis, тому бронювання не залежить від годинника чи таймерів
 * конкретного JVM-процесу і переживає перезапуск будь-якого екземпляра сервісу.
 */
@Component
public class ListingHoldStore {

    private static final String KEY_PREFIX = "hold:listing:";

    /** Атомарне «видалити, лише якщо власник збігається» (compare-and-delete). */
    private static final RedisScript<Long> RELEASE_IF_OWNER = new DefaultRedisScript<>("""
            if redis.call('GET', KEYS[1]) == ARGV[1] then
                return redis.call('DEL', KEYS[1])
            end
            return 0
            """, Long.class);

    private final StringRedisTemplate redis;

    public ListingHoldStore(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /** SET key buyerId NX PX ttl — атомарно; true, якщо бронювання створено цим викликом. */
    public boolean tryAcquire(Long listingId, Long buyerId, Duration ttl) {
        return Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(key(listingId), buyerId.toString(), ttl));
    }

    public Optional<Long> findHolder(Long listingId) {
        return Optional.ofNullable(redis.opsForValue().get(key(listingId))).map(Long::valueOf);
    }

    /** Залишок часу бронювання в секундах (0, якщо бронювання немає). */
    public long secondsLeft(Long listingId) {
        Long seconds = redis.getExpire(key(listingId), TimeUnit.SECONDS);
        return seconds == null || seconds < 0 ? 0 : seconds;
    }

    public boolean release(Long listingId, Long buyerId) {
        Long deleted = redis.execute(RELEASE_IF_OWNER, List.of(key(listingId)), buyerId.toString());
        return deleted != null && deleted > 0;
    }

    public void forceRelease(Long listingId) {
        redis.delete(key(listingId));
    }

    private static String key(Long listingId) {
        return KEY_PREFIX + listingId;
    }
}
