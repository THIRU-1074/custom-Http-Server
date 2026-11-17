package in.jolt;

import in.jolt.internal.limit.FixedWindow;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.function.Function;
import java.util.function.LongSupplier;

/** Opt-in rate limiting middleware. */
public final class RateLimit {

    private static final int MAX_TRACKED_ADDRESSES = 10_000;

    private RateLimit() {}

    /**
     * Allows {@code maxRequests} per remote address in each fixed {@code window}; further requests
     * get 429 with {@code Retry-After}. IPv6 is keyed by its /64 prefix and IPv4-mapped IPv6 as the
     * IPv4. Behind a proxy set {@link JoltConfig#trustProxy} so the forwarded address is used, or
     * use {@link #perKey}. At most 10000 addresses are tracked.
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
        return perKey(maxRequests, window, RateLimit::ipKey, nanos, maxKeys);
    }

    /**
     * Like {@link #perIp(int, Duration)} but buckets by the string {@code key} returns, for example
     * a validated forwarded address or an API key. A {@code null} key shares one bucket.
     */
    public static Middleware perKey(
            int maxRequests, Duration window, Function<Request, String> key) {
        return perKey(maxRequests, window, key, System::nanoTime, MAX_TRACKED_ADDRESSES);
    }

    static Middleware perKey(
            int maxRequests,
            Duration window,
            Function<Request, String> keyFn,
            LongSupplier nanos,
            int maxKeys) {
        if (keyFn == null) {
            throw new IllegalArgumentException("key function is required");
        }
        if (maxRequests < 1 || window == null || window.isZero() || window.isNegative()) {
            throw new IllegalArgumentException("Need maxRequests >= 1 and a positive window");
        }
        if (maxKeys < 1) {
            throw new IllegalArgumentException("Need maxTrackedAddresses >= 1");
        }
        long windowNanos;
        try {
            windowNanos = window.toNanos();
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException("window is too large", e);
        }
        FixedWindow counter = new FixedWindow(maxRequests, windowNanos, maxKeys, nanos);
        return (req, res, next) -> {
            String key = keyFn.apply(req);
            long retryNanos = counter.acquire(key == null ? "unknown" : key);
            if (retryNanos >= 0) {
                res.header("Retry-After", String.valueOf(FixedWindow.seconds(retryNanos)))
                        .status(429)
                        .text("Too Many Requests");
                return;
            }
            next.run();
        };
    }

    private static String ipKey(Request req) {
        String addr = req.remoteAddress();
        if (addr == null) {
            return null;
        }
        int scope = addr.indexOf('%');
        if (scope >= 0) {
            addr = addr.substring(0, scope);
        }
        if (addr.indexOf(':') < 0) {
            return addr;
        }
        try {
            InetAddress ip = InetAddress.getByName(addr);
            if (ip instanceof Inet4Address) {
                return ip.getHostAddress();
            }
            byte[] b = ip.getAddress();
            return HexFormat.of().formatHex(b, 0, 8) + "::/64";
        } catch (UnknownHostException | RuntimeException e) {
            return addr;
        }
    }
}
