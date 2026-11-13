package com.athletex.ratelimit;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Token-bucket limits: order entry per user, sign-in and registration per client address.
 * Buckets live in this instance's memory, so with several instances behind a load balancer the
 * effective limit multiplies; moving the buckets to Redis is the fix at that point.
 */
public class RateLimitFilter extends OncePerRequestFilter {

    public record Limit(int capacity, int refillTokens, Duration refillPeriod) {
    }

    private static final class Bucket {
        private final Limit limit;
        private double tokens;
        private long lastRefillNanos;
        private volatile long lastUsedNanos;

        Bucket(Limit limit, long now) {
            this.limit = limit;
            this.tokens = limit.capacity();
            this.lastRefillNanos = now;
            this.lastUsedNanos = now;
        }

        /** Returns 0 if a token was taken, otherwise the seconds until one is available. */
        synchronized long tryTake(long now) {
            double perNano = (double) limit.refillTokens() / limit.refillPeriod().toNanos();
            tokens = Math.min(limit.capacity(), tokens + (now - lastRefillNanos) * perNano);
            lastRefillNanos = now;
            lastUsedNanos = now;
            if (tokens >= 1) {
                tokens -= 1;
                return 0;
            }
            return Math.max(1, (long) Math.ceil((1 - tokens) / perNano / 1_000_000_000.0));
        }
    }

    private final Limit trading;
    private final Limit signIn;
    private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();

    public RateLimitFilter(Limit trading, Limit signIn) {
        this.trading = trading;
        this.signIn = signIn;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String method = request.getMethod();
        String path = request.getRequestURI();
        String key = null;
        Limit limit = null;
        if ("POST".equals(method) && (path.equals("/api/auth/login") || path.equals("/api/auth/register"))) {
            key = "auth:" + request.getRemoteAddr();
            limit = signIn;
        } else if (path.startsWith("/api/orders") || path.startsWith("/api/ipo/bids")) {
            if (!"GET".equals(method)) {
                Authentication auth = SecurityContextHolder.getContext().getAuthentication();
                if (auth != null && auth.isAuthenticated()) {
                    key = "trade:" + auth.getName();
                    limit = trading;
                }
            }
        }
        if (key != null) {
            long now = System.nanoTime();
            Limit chosen = limit;
            long retryAfter = buckets.computeIfAbsent(key, k -> new Bucket(chosen, now)).tryTake(now);
            if (retryAfter > 0) {
                response.setStatus(429);
                response.setHeader("Retry-After", Long.toString(retryAfter));
                response.setContentType("application/problem+json");
                response.getWriter().write("{\"status\":429,\"title\":\"Too Many Requests\",\"detail\":\"Too many requests. Try again in "
                        + retryAfter + (retryAfter == 1 ? " second" : " seconds") + ".\"}");
                return;
            }
        }
        chain.doFilter(request, response);
    }

    /** Drops buckets idle for longer than {@code idle}, so the map does not grow without bound. */
    public int evictIdle(Duration idle) {
        long cutoff = System.nanoTime() - idle.toNanos();
        int before = buckets.size();
        buckets.values().removeIf(b -> b.lastUsedNanos < cutoff);
        return before - buckets.size();
    }
}
