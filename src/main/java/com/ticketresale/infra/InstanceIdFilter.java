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
 * Додає до кожної відповіді заголовок X-Instance-ID — ідентифікатор екземпляра,
 * що обробив запит. Заголовок встановлюється до обробки, тому присутній і у відповідях з помилками.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class InstanceIdFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Instance-ID";

    private final String instanceId;

    public InstanceIdFilter(InstanceProperties properties) {
        this.instanceId = properties.instanceId();
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        response.setHeader(HEADER, instanceId);
        chain.doFilter(request, response);
    }
}
