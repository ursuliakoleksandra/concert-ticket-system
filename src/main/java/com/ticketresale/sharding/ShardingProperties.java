package com.ticketresale.sharding;

import java.util.ArrayList;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Налаштування шардингу (resale.sharding.*), ЛР7.
 *
 * @param strategy     функція маршрутизації: modulo | hash | consistent
 * @param virtualNodes кількість віртуальних вузлів кожного шарду на кільці Consistent Hashing
 * @param shards       список шардів «ім'я=JDBC URL» через кому; один елемент — топологія Single Node
 * @param poolSize     розмір пулу з'єднань до кожного шарду на одному вузлі API
 */
@ConfigurationProperties(prefix = "resale.sharding")
public record ShardingProperties(StrategyType strategy, int virtualNodes, String shards, int poolSize) {

    public enum StrategyType { MODULO, HASH, CONSISTENT }

    public record ShardSpec(String name, String url) {
    }

    /** Розбір рядка {@code shard-a=jdbc:...,shard-b=jdbc:...}; порядок визначає індекс шарду. */
    public List<ShardSpec> shardSpecs() {
        List<ShardSpec> result = new ArrayList<>();
        for (String item : shards.split(",")) {
            String trimmed = item.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            int eq = trimmed.indexOf('=');
            if (eq <= 0) {
                throw new IllegalArgumentException("Шард має бути у форматі ім'я=jdbc-url: " + trimmed);
            }
            result.add(new ShardSpec(trimmed.substring(0, eq).trim(), trimmed.substring(eq + 1).trim()));
        }
        if (result.isEmpty()) {
            throw new IllegalArgumentException("Не задано жодного шарду (resale.sharding.shards)");
        }
        return result;
    }
}
