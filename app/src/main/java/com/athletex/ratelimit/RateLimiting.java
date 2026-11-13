package com.athletex.ratelimit;

import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Owns the rate-limit filter. The filter is deliberately not a bean itself: Spring Boot would
 * also register a Filter bean with the servlet container, running it twice. SecurityConfig adds
 * it to the security chain after token authentication, so per-user limits know the user.
 */
@Component
public class RateLimiting {

    private final RateLimitFilter filter;

    public RateLimiting(@Value("${athletex.rate-limit.trading.capacity:20}") int tradingCapacity,
                        @Value("${athletex.rate-limit.trading.per-second:10}") int tradingPerSecond,
                        @Value("${athletex.rate-limit.sign-in.capacity:10}") int signInCapacity,
                        @Value("${athletex.rate-limit.sign-in.per-minute:10}") int signInPerMinute) {
        this.filter = new RateLimitFilter(
                new RateLimitFilter.Limit(tradingCapacity, tradingPerSecond, Duration.ofSeconds(1)),
                new RateLimitFilter.Limit(signInCapacity, signInPerMinute, Duration.ofMinutes(1)));
    }

    public RateLimitFilter filter() {
        return filter;
    }

    @Scheduled(fixedDelay = 300_000)
    public void evictIdleBuckets() {
        filter.evictIdle(Duration.ofMinutes(10));
    }
}
