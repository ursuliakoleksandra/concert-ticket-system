package com.ticketresale.sharding;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import com.ticketresale.sharding.ShardingProperties.StrategyType;

/**
 * Функція маршрутизації «ключ шардингу → індекс шарду». Детермінована: той самий ключ
 * за тієї самої конфігурації завжди потрапляє на той самий шард, на будь-якому вузлі API.
 */
public interface ShardingStrategy {

    int shardIndex(long key);

    String description();

    static ShardingStrategy create(StrategyType type, List<String> shardNames, int virtualNodes) {
        return switch (type) {
            case MODULO -> new Modulo(shardNames.size());
            case HASH -> new Hash(shardNames.size());
            case CONSISTENT -> new ConsistentHash(shardNames, virtualNodes);
        };
    }

    /** Наївний варіант: key % N. Чутливий до структури ключів і переносить майже всі дані при зміні N. */
    record Modulo(int shards) implements ShardingStrategy {
        @Override
        public int shardIndex(long key) {
            return (int) Math.floorMod(key, (long) shards);
        }

        @Override
        public String description() {
            return "key % " + shards;
        }
    }

    /** hash(key) % N: рівномірний розподіл незалежно від структури ключів, але зміна N так само переносить ~(N−1)/N даних. */
    record Hash(int shards) implements ShardingStrategy {
        @Override
        public int shardIndex(long key) {
            return Math.floorMod(Hashing.mix64(key), shards);
        }

        @Override
        public String description() {
            return "murmur3(key) % " + shards;
        }
    }

    /**
     * Consistent Hashing з віртуальними вузлами: кожен шард займає {@code virtualNodes} точок на кільці 2^64.
     * Ключ належить першому віртуальному вузлу за годинниковою стрілкою від hash(key).
     * Додавання шарду забирає лише ~1/(N+1) ключів — у сусідів на кільці, решта лишається на місці.
     */
    final class ConsistentHash implements ShardingStrategy {

        private final TreeMap<Long, Integer> ring = new TreeMap<>();
        private final int virtualNodes;
        private final int shards;

        public ConsistentHash(List<String> shardNames, int virtualNodes) {
            this.virtualNodes = virtualNodes;
            this.shards = shardNames.size();
            for (int i = 0; i < shardNames.size(); i++) {
                for (int v = 0; v < virtualNodes; v++) {
                    ring.put(Hashing.fnv1a64(shardNames.get(i) + "#vn" + v), i);
                }
            }
        }

        @Override
        public int shardIndex(long key) {
            Map.Entry<Long, Integer> owner = ring.ceilingEntry(Hashing.mix64(key));
            return owner != null ? owner.getValue() : ring.firstEntry().getValue();
        }

        @Override
        public String description() {
            return "consistent hashing, " + shards + " шард(и) × " + virtualNodes + " віртуальних вузлів";
        }
    }
}
