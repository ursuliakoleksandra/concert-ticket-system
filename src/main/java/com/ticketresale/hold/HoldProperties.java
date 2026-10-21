package com.ticketresale.hold;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** @param ttl тривалість бронювання оголошення (resale.hold.ttl) */
@ConfigurationProperties(prefix = "resale.hold")
public record HoldProperties(Duration ttl) {
}
