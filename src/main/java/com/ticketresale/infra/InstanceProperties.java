package com.ticketresale.infra;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Параметри конкретного екземпляра сервісу (app.*).
 * Задаються лише змінними оточення контейнера — у пам'яті нічого не змінюється під час роботи.
 *
 * @param instanceId        ідентифікатор екземпляра для заголовка X-Instance-ID
 * @param artificialDelayMs штучна затримка обробки запиту (експеримент з повільним вузлом)
 */
@ConfigurationProperties(prefix = "app")
public record InstanceProperties(String instanceId, long artificialDelayMs) {
}
