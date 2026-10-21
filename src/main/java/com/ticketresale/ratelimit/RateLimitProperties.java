package com.ticketresale.ratelimit;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** @param requestsPerMinute ліміт запитів бронювання/купівлі на користувача за хвилину */
@ConfigurationProperties(prefix = "resale.rate-limit")
public record RateLimitProperties(int requestsPerMinute) {
}
