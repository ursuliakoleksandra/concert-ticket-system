package com.ticketresale.common;

import java.math.BigDecimal;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Бізнес-правила перепродажу, що задаються в application.yml (resale.rules.*).
 *
 * @param maxMarkup          максимальний множник ціни відносно номіналу
 * @param maxTicketsPerEvent ліміт покупок на одну подію для одного покупця
 */
@ConfigurationProperties(prefix = "resale.rules")
public record ResaleRules(BigDecimal maxMarkup, int maxTicketsPerEvent) {
}
