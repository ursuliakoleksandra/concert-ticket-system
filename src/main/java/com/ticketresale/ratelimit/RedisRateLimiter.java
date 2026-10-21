package com.ticketresale.ratelimit;

import java.util.List;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

/**
 * Розподілений лічильник запитів (fixed window) у Redis.
 * <p>
 * Лічильник спільний для всіх екземплярів: локальний лічильник у пам'яті кожного процесу
 * дозволив би при N екземплярах та балансуванні Round Robin виконати N × ліміт запитів.
 * Вікно відраховується від першого запиту за TTL ключа, тобто за годинником Redis,
 * а не JVM, тому розбіжність годинників між екземплярами не впливає на результат.
 */
@Component
public class RedisRateLimiter {

    private static final long WINDOW_SECONDS = 60;

    /** INCR + EXPIRE атомарно; повертає {поточна кількість, секунд до скидання вікна}. */
    @SuppressWarnings("rawtypes")
    private static final RedisScript<List> INCREMENT = new DefaultRedisScript<>("""
            local count = redis.call('INCR', KEYS[1])
            if count == 1 then
                redis.call('EXPIRE', KEYS[1], ARGV[1])
            end
            return {count, redis.call('TTL', KEYS[1])}
            """, List.class);

    private final StringRedisTemplate redis;
    private final int limit;

    public RedisRateLimiter(StringRedisTemplate redis, RateLimitProperties properties) {
        this.redis = redis;
        this.limit = properties.requestsPerMinute();
    }

    /** @return 0, якщо запит дозволено; інакше — кількість секунд до скидання вікна */
    public long acquire(String subject) {
        List<?> result = redis.execute(INCREMENT, List.of("rl:" + subject), String.valueOf(WINDOW_SECONDS));
        long count = ((Number) result.get(0)).longValue();
        long ttl = ((Number) result.get(1)).longValue();
        return count <= limit ? 0 : Math.max(ttl, 1);
    }
}
