package com.ticketresale.sharding;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.ticketresale.common.exception.BusinessRuleException;
import com.ticketresale.sharding.ShardingProperties.StrategyType;

/**
 * Службові endpoint-и для демонстрації маршрутизації (ЛР7). Лише для лабораторного стенду:
 * у реальній системі закриваються автентифікацією.
 */
@RestController
@RequestMapping("/api/admin/sharding")
public class ShardingAdminController {

    private final ShardRouter router;

    public ShardingAdminController(ShardRouter router) {
        this.router = router;
    }

    /** Поточна топологія: шарди та функція маршрутизації. */
    @GetMapping
    public Map<String, Object> topology() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("strategy", router.properties().strategy().name().toLowerCase());
        body.put("description", router.strategy().description());
        body.put("shards", router.all().stream().map(Shard::name).toList());
        return body;
    }

    /** На який шард потрапляє ключ — за поточною функцією і за кожною з трьох для порівняння. */
    @GetMapping("/route/{key}")
    public Map<String, Object> route(@PathVariable long key) {
        List<String> names = router.all().stream().map(Shard::name).toList();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("key", key);
        body.put("shard", router.route(key).name());
        Map<String, String> byStrategy = new LinkedHashMap<>();
        for (StrategyType type : StrategyType.values()) {
            ShardingStrategy s = ShardingStrategy.create(type, names, router.properties().virtualNodes());
            byStrategy.put(type.name().toLowerCase(), names.get(s.shardIndex(key)));
        }
        body.put("byStrategy", byStrategy);
        return body;
    }

    /** Модель решардингу: частка ключів, що переносяться при зміні from → to шардів. */
    @GetMapping("/resharding")
    public Map<String, Object> resharding(@RequestParam(defaultValue = "2") int from,
                                          @RequestParam(defaultValue = "3") int to,
                                          @RequestParam(defaultValue = "100000") int keys) {
        if (from < 1 || to < 1 || from > 64 || to > 64 || keys < 1 || keys > 2_000_000) {
            throw new BusinessRuleException("Допустимо: 1 ≤ from, to ≤ 64; 1 ≤ keys ≤ 2 000 000");
        }
        return ReshardingSimulator.simulate(from, to, keys, router.properties().virtualNodes());
    }
}
