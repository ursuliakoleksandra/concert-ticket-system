package com.ticketresale.ratelimit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Обмеження частоти бронювань і купівель на одного користувача (протидія ботам).
 * Якщо Redis недоступний — запит пропускається (fail-open): основні гарантії
 * (одна покупка на оголошення, ліміт квитків на подію) забезпечує PostgreSQL.
 */
@Component
public class RateLimitInterceptor implements HandlerInterceptor {

    private static final Logger log = LoggerFactory.getLogger(RateLimitInterceptor.class);

    private final RedisRateLimiter limiter;

    public RateLimitInterceptor(RedisRateLimiter limiter) {
        this.limiter = limiter;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String userId = request.getHeader("X-User-Id");
        if (!"POST".equals(request.getMethod()) || userId == null || userId.isBlank()) {
            return true; // відсутній заголовок обробить контролер (400)
        }
        long retryAfter;
        try {
            retryAfter = limiter.acquire("user:" + userId.trim());
        } catch (DataAccessException e) {
            log.warn("Rate limiter недоступний, запит пропущено: {}", e.getMessage());
            return true;
        }
        if (retryAfter > 0) {
            throw new TooManyRequestsException(retryAfter);
        }
        return true;
    }
}
