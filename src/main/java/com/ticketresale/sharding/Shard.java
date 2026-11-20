package com.ticketresale.sharding;

import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Фізичний шард: окремий вузол PostgreSQL зі своїм пулом з'єднань.
 *
 * @param index порядковий номер у конфігурації (результат функції маршрутизації)
 * @param name  ім'я шарду (повертається в заголовку X-Shard)
 */
public record Shard(int index, String name, JdbcTemplate jdbc) {
}
