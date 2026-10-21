package com.ticketresale.infra;

import java.io.IOException;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Синтетична затримка обробки запитів (ЛР3, експеримент з асиметричним навантаженням).
 * Вмикається змінною ARTIFICIAL_DELAY_MS лише на обраному екземплярі.
 * Health check не сповільнюється, щоб вузол лишався «живим», але повільним (degraded).
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
public class ArtificialDelayFilter extends OncePerRequestFilter {

    private final long delayMs;

    public ArtificialDelayFilter(InstanceProperties properties) {
        this.delayMs = properties.artificialDelayMs();
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String uri = request.getRequestURI();
        return delayMs <= 0 || uri.startsWith("/health") || uri.startsWith("/actuator");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        try {
            Thread.sleep(delayMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        chain.doFilter(request, response);
    }
}
