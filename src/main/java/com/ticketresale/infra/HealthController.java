package com.ticketresale.infra;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;

import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * GET /health — перевірка готовності екземпляра для балансувальника та Docker healthcheck.
 * <ul>
 *   <li>БД недоступна → 503 DOWN: екземпляр не може обробити жодного бізнес-запиту;</li>
 *   <li>Redis недоступний → 200 DEGRADED: каталог і перегляд працюють, бронювання — ні.
 *       Redis спільний для всіх екземплярів, тому виключення вузла з пулу не допоможе.</li>
 * </ul>
 */
@RestController
public class HealthController {

    private static final String UP = "UP";
    private static final String DOWN = "DOWN";

    private final JdbcTemplate jdbc;
    private final StringRedisTemplate redis;
    private final String instanceId;

    public HealthController(JdbcTemplate jdbc, StringRedisTemplate redis, InstanceProperties properties) {
        this.jdbc = jdbc;
        this.redis = redis;
        this.instanceId = properties.instanceId();
    }

    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> health() {
        String db = probe(() -> jdbc.queryForObject("SELECT 1", Integer.class));
        String cache = probe(() -> redis.execute((RedisCallback<String>) connection -> connection.ping()));

        boolean ready = UP.equals(db);
        String status = !ready ? DOWN : (UP.equals(cache) ? UP : "DEGRADED");

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", status);
        body.put("instance", instanceId);
        body.put("checks", Map.of("database", db, "redis", cache));
        return ResponseEntity.status(ready ? HttpStatus.OK : HttpStatus.SERVICE_UNAVAILABLE).body(body);
    }

    private static String probe(Supplier<?> check) {
        try {
            check.get();
            return UP;
        } catch (RuntimeException e) {
            return DOWN;
        }
    }
}
