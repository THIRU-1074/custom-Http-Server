package in.jolt;

import java.time.Duration;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.function.LongSupplier;

/** Opt-in rate limiting middleware. */
public final class RateLimit {

    private static final int MAX_TRACKED_ADDRESSES = 10_000;

    private RateLimit() {}

    /**
     * Allows {@code maxRequests} per remote address in each fixed {@code window}; further requests
     * get 429 with {@code Retry-After}. Memory is bounded: expired entries are evicted and at most
     * 10000 addresses are tracked, after which new addresses get 429 until entries expire.
     */
    public static Middleware perIp(int maxRequests, Duration window) {
        return perIp(maxRequests, window, System::nanoTime, MAX_TRACKED_ADDRESSES);
    }

    static Middleware perIp(int maxRequests, Duration window, LongSupplier nanos, int maxKeys) {
        if (maxRequests < 1 || window == null || window.isZero() || window.isNegative()) {
            throw new IllegalArgumentException("Need maxRequests >= 1 and a positive window");
        }
        long windowNanos = window.toNanos();
        Map<String, long[]> table = new HashMap<>();
        return (req, res, next) -> {
            String key = req.remoteAddress() == null ? "unknown" : req.remoteAddress();
            long now = nanos.getAsLong();
            long retryNanos = -1;
            synchronized (table) {
                long[] entry = table.get(key);
                if (entry == null) {
                    if (table.size() >= maxKeys) {
                        evictExpired(table, now, windowNanos);
                    }
                    if (table.size() >= maxKeys) {
                        retryNanos = windowNanos;
                    } else {
                        table.put(key, new long[] {now, 1});
                    }
                } else if (now - entry[0] >= windowNanos) {
                    entry[0] = now;
                    entry[1] = 1;
                } else if (entry[1] < maxRequests) {
                    entry[1]++;
                } else {
                    retryNanos = windowNanos - (now - entry[0]);
                }
            }
            if (retryNanos >= 0) {
                long seconds = Math.max(1, (retryNanos + 999_999_999L) / 1_000_000_000L);
                res.header("Retry-After", String.valueOf(seconds))
                        .status(429)
                        .text("Too Many Requests");
                return;
            }
            next.run();
        };
    }

    private static void evictExpired(Map<String, long[]> table, long now, long windowNanos) {
        for (Iterator<long[]> it = table.values().iterator(); it.hasNext(); ) {
            if (now - it.next()[0] >= windowNanos) {
                it.remove();
            }
        }
    }
}
