package com.ticketresale.infra;

import java.time.Duration;

import org.springframework.boot.autoconfigure.data.redis.LettuceClientConfigurationBuilderCustomizer;
import org.springframework.boot.autoconfigure.data.redis.RedisProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.lettuce.core.ClientOptions;
import io.lettuce.core.SocketOptions;
import io.lettuce.core.TimeoutOptions;

/**
 * Поведінка клієнта Redis (Lettuce) при відмові Redis (ЛР4, Fallback).
 * <p>
 * За замовчуванням Lettuce при розриві з'єднання ставить команди в чергу до відновлення,
 * і кожен запит чекав би на тайм-аут (2 с). З REJECT_COMMANDS команди одразу відхиляються,
 * тож застосунок миттєво переходить на читання з БД (X-Cache: BYPASS) без додаткової затримки.
 * Автоматичне перепідключення залишається увімкненим — після повернення Redis кеш відновлюється сам.
 */
@Configuration
public class RedisClientConfig {

    @Bean
    LettuceClientConfigurationBuilderCustomizer failFastWhenRedisIsDown(RedisProperties properties) {
        Duration connectTimeout = properties.getConnectTimeout() != null
                ? properties.getConnectTimeout()
                : Duration.ofMillis(500);
        return builder -> builder.clientOptions(ClientOptions.builder()
                .autoReconnect(true)
                .disconnectedBehavior(ClientOptions.DisconnectedBehavior.REJECT_COMMANDS)
                .socketOptions(SocketOptions.builder().connectTimeout(connectTimeout).build())
                .timeoutOptions(TimeoutOptions.enabled())
                .build());
    }
}
