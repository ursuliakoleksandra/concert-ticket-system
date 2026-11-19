package com.ticketresale.event;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param queryCost додаткова тривалість агрегації статистики в СУБД ({@code pg_sleep}).
 *                  Імітує коштовний аналітичний запит, щоб наслідки Cache Stampede було видно
 *                  на невеликому лабораторному наборі даних (ЛР8). 0 — без імітації.
 */
@ConfigurationProperties(prefix = "resale.stats")
public record EventStatsProperties(Duration queryCost) {
}
