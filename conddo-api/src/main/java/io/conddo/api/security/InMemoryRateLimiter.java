package io.conddo.api.security;

import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * A small in-memory fixed-window rate limiter for the PUBLIC endpoints (§11.5).
 * Single-instance only — fine for the current single Render dyno; move to a
 * shared store (Redis) when scaling horizontally. Defaults: 20 requests per
 * 60 seconds per key (key = client IP + bucket name).
 */
@Component
public class InMemoryRateLimiter {

    private static final int MAX_REQUESTS = 20;
    private static final long WINDOW_MILLIS = 60_000L;

    private final ConcurrentMap<String, Counter> counters = new ConcurrentHashMap<>();

    /** Returns true if the call is allowed; false once the window's quota is spent. */
    public boolean tryAcquire(String key) {
        long now = System.currentTimeMillis();
        Counter counter = counters.compute(key, (k, existing) -> {
            if (existing == null || now - existing.windowStart >= WINDOW_MILLIS) {
                return new Counter(now);
            }
            existing.count++;
            return existing;
        });
        return counter.count <= MAX_REQUESTS;
    }

    private static final class Counter {
        private final long windowStart;
        private int count;

        private Counter(long windowStart) {
            this.windowStart = windowStart;
            this.count = 1;
        }
    }
}
