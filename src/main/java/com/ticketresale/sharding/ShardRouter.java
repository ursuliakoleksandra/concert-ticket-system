package com.ticketresale.sharding;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.flywaydb.core.Flyway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import com.zaxxer.hikari.HikariDataSource;

import jakarta.annotation.PreDestroy;

/**
 * Shard Router (Application-Level Sharding), ЛР7.
 * <p>
 * Обчислює цільовий шард за ключем шардингу ({@code user_id}) і повертає його пул з'єднань.
 * Маршрутизація детермінована й не має стану: усі вузли API з однаковою конфігурацією
 * спрямовують той самий ключ на той самий шард без звернення до спільного каталогу.
 * <p>
 * Під час старту схема кожного шарду створюється міграціями Flyway з {@code classpath:db/shard}.
 */
@Component
public class ShardRouter {

    private static final Logger log = LoggerFactory.getLogger(ShardRouter.class);
    public static final String HEADER = "X-Shard";

    private final List<Shard> shards;
    private final List<HikariDataSource> pools = new ArrayList<>();
    private final ShardingStrategy strategy;
    private final ShardingProperties properties;

    public ShardRouter(ShardingProperties properties, DataSourceProperties db) {
        this.properties = properties;
        List<Shard> list = new ArrayList<>();
        List<String> names = new ArrayList<>();
        for (ShardingProperties.ShardSpec spec : properties.shardSpecs()) {
            HikariDataSource pool = new HikariDataSource();
            pool.setPoolName("shard-" + spec.name());
            pool.setJdbcUrl(spec.url());
            pool.setUsername(db.getUsername());
            pool.setPassword(db.getPassword());
            pool.setMaximumPoolSize(properties.poolSize());
            pool.setConnectionTimeout(3000);
            pool.setInitializationFailTimeout(-1);
            pools.add(pool);
            migrate(spec.name(), pool);
            list.add(new Shard(list.size(), spec.name(), new JdbcTemplate(pool)));
            names.add(spec.name());
        }
        this.shards = Collections.unmodifiableList(list);
        this.strategy = ShardingStrategy.create(properties.strategy(), names, properties.virtualNodes());
        log.info("Шардинг: {} ({})", names, strategy.description());
    }

    /** Шард, якому належить ключ (Shard-Local операція). */
    public Shard route(long shardKey) {
        return shards.get(strategy.shardIndex(shardKey));
    }

    /** Усі шарди — для Cross-Shard запитів (Scatter-Gather). */
    public List<Shard> all() {
        return shards;
    }

    public ShardingStrategy strategy() {
        return strategy;
    }

    public ShardingProperties properties() {
        return properties;
    }

    /** Недоступний шард не зупиняє застосунок: операції з його ключами отримають 503. */
    private static void migrate(String name, HikariDataSource pool) {
        try {
            Flyway.configure()
                    .dataSource(pool)
                    .locations("classpath:db/shard")
                    .load()
                    .migrate();
        } catch (RuntimeException e) {
            log.error("Не вдалося застосувати міграції шарду {}: {}", name, e.getMessage());
        }
    }

    @PreDestroy
    void close() {
        pools.forEach(HikariDataSource::close);
    }
}
