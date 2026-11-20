package com.ticketresale.sharding;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.ticketresale.sharding.ShardingProperties.StrategyType;

/**
 * Модель решардингу (ЛР7): яку частку ключів довелося б перенести при зміні кількості шардів
 * з {@code from} на {@code to} для кожної функції маршрутизації.
 * <p>
 * Ключ переноситься, якщо його шард у новій топології відрізняється від старого.
 * Для key % N і hash(key) % N частка ≈ 1 − 1/to (для 2 → 3: 66,7 %, для 10 → 11: 90,9 %),
 * для Consistent Hashing ≈ |to − from| / max(from, to) (для 2 → 3: 33,3 %, тобто лише ключі нового шарду).
 */
public final class ReshardingSimulator {

    private ReshardingSimulator() {
    }

    public static Map<String, Object> simulate(int from, int to, int keys, int virtualNodes) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (StrategyType type : StrategyType.values()) {
            ShardingStrategy before = ShardingStrategy.create(type, names(from), virtualNodes);
            ShardingStrategy after = ShardingStrategy.create(type, names(to), virtualNodes);
            long moved = 0;
            long[] distribution = new long[to];
            for (long key = 1; key <= keys; key++) {
                int target = after.shardIndex(key);
                distribution[target]++;
                if (before.shardIndex(key) != target) {
                    moved++;
                }
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("strategy", type.name().toLowerCase());
            row.put("description", after.description());
            row.put("movedKeys", moved);
            row.put("movedPercent", round(100.0 * moved / keys));
            List<Double> shares = new ArrayList<>();
            for (long count : distribution) {
                shares.add(round(100.0 * count / keys));
            }
            row.put("sharePercentAfter", shares);
            rows.add(row);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("from", from);
        result.put("to", to);
        result.put("keys", keys);
        result.put("idealMovedPercent", round(100.0 * Math.abs(to - from) / Math.max(from, to)));
        result.put("strategies", rows);
        return result;
    }

    /** Імена шардів shard-1 … shard-N: нові шарди додаються в кінець, існуючі імена не змінюються. */
    private static List<String> names(int count) {
        List<String> names = new ArrayList<>();
        for (int i = 1; i <= count; i++) {
            names.add("shard-" + i);
        }
        return names;
    }

    private static double round(double value) {
        return Math.round(value * 100.0) / 100.0;
    }
}
