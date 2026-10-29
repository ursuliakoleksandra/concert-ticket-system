package com.ticketresale.cache;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Налаштування розподіленого кешу (resale.cache.*).
 *
 * @param enabled     увімкнення кешу; false — усі читання напряму з БД (режим Cache Disabled для ЛР5)
 * @param listingsTtl базовий TTL сторінки оголошень події
 * @param ttlJitter   випадкове відхилення TTL (0.2 = ±20 %) — протидія Cache Avalanche
 */
@ConfigurationProperties(prefix = "resale.cache")
public record CacheProperties(boolean enabled, Duration listingsTtl, double ttlJitter) {
}
