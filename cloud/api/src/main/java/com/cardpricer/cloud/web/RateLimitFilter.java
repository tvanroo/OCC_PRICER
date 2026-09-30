package com.cardpricer.cloud.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-IP fixed-window limit for anonymous endpoints (free price check and sign-in),
 * so free traffic stays cheap and passwords cannot be brute-forced quickly.
 * In memory per replica; good enough while the API runs one or two replicas.
 */
@Component
public class RateLimitFilter extends OncePerRequestFilter {
    private record Window(long minute, int count) {}

    private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();
    private final int publicPerMinute;
    private final int authPerMinute;

    public RateLimitFilter(@Value("${app.rate-limit.public-per-minute:60}") int publicPerMinute,
                           @Value("${app.rate-limit.auth-per-minute:10}") int authPerMinute) {
        this.publicPerMinute = publicPerMinute;
        this.authPerMinute = authPerMinute;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return !path.startsWith("/api/public/") && !path.startsWith("/api/auth/login") && !path.startsWith("/api/auth/signup");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        boolean auth = request.getRequestURI().startsWith("/api/auth/");
        String key = (auth ? "auth:" : "public:") + ClientIp.of(request);
        long minute = System.currentTimeMillis() / 60_000;
        Window window = windows.compute(key, (k, w) -> w == null || w.minute() != minute ? new Window(minute, 1) : new Window(minute, w.count() + 1));
        if (windows.size() > 50_000) windows.entrySet().removeIf(e -> e.getValue().minute() != minute);
        if (window.count() > (auth ? authPerMinute : publicPerMinute)) {
            response.setStatus(429);
            response.setContentType("application/json");
            response.getWriter().write("{\"error\":\"Too many requests, please wait a minute\"}");
            return;
        }
        chain.doFilter(request, response);
    }
}
