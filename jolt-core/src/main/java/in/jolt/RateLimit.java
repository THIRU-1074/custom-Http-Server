package in.jolt;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.LongSupplier;

/** Opt-in rate limiting middleware. */
public final class RateLimit {

    private static final int MAX_TRACKED_ADDRESSES = 10_000;

    private RateLimit() {}

    /**
     * Allows {@code maxRequests} per remote address in each fixed {@code window}; further requests
     * get 429 with {@code Retry-After}. Memory is bounded: at most 10000 addresses are tracked.
     */
    public static Middleware perIp(int maxRequests, Duration window) {
        return perIp(maxRequests, window, MAX_TRACKED_ADDRESSES);
    }

    /**
     * Like {@link #perIp(int, Duration)} with a custom cap on tracked addresses. When the table is
     * full the least recently used entry is evicted in O(1), so new addresses are always served;
     * the trade-off is that a flood of distinct addresses can reset the counters of idle ones.
     */
    public static Middleware perIp(int maxRequests, Duration window, int maxTrackedAddresses) {
        return perIp(maxRequests, window, System::nanoTime, maxTrackedAddresses);
    }

    static Middleware perIp(int maxRequests, Duration window, LongSupplier nanos, int maxKeys) {
        if (maxRequests < 1 || window == null || window.isZero() || window.isNegative()) {
            throw new IllegalArgumentException("Need maxRequests >= 1 and a positive window");
        }
        if (maxKeys < 1) {
            throw new IllegalArgumentException("Need maxTrackedAddresses >= 1");
        }
        long windowNanos = window.toNanos();
        Map<String, long[]> table =
                new LinkedHashMap<>(16, 0.75f, true) {
                    @Override
                    protected boolean removeEldestEntry(Map.Entry<String, long[]> eldest) {
                        return size() > maxKeys;
                    }
                };
        return (req, res, next) -> {
            String key = req.remoteAddress() == null ? "unknown" : req.remoteAddress();
            long now = nanos.getAsLong();
            long retryNanos = -1;
            synchronized (table) {
                long[] entry = table.get(key);
                if (entry == null) {
                    table.put(key, new long[] {now, 1});
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
}
