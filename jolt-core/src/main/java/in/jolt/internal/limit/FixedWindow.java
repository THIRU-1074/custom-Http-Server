package in.jolt.internal.limit;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.LongSupplier;

/**
 * Fixed-window counter per key, bounded to {@code maxKeys} entries with LRU eviction. Callers
 * validate arguments; this class assumes they are sane.
 */
public final class FixedWindow {

    private final int max;
    private final long windowNanos;
    private final LongSupplier nanos;
    private final Map<String, long[]> table;

    public FixedWindow(int max, long windowNanos, int maxKeys, LongSupplier nanos) {
        this.max = max;
        this.windowNanos = windowNanos;
        this.nanos = nanos;
        this.table =
                new LinkedHashMap<>(16, 0.75f, true) {
                    @Override
                    protected boolean removeEldestEntry(Map.Entry<String, long[]> eldest) {
                        return size() > maxKeys;
                    }
                };
    }

    /** Counts one hit for {@code key}: -1 when allowed, else the nanos until the window resets. */
    public long acquire(String key) {
        long now = nanos.getAsLong();
        synchronized (table) {
            long[] entry = table.get(key);
            if (entry == null) {
                table.put(key, new long[] {now, 1});
            } else if (now - entry[0] >= windowNanos) {
                entry[0] = now;
                entry[1] = 1;
            } else if (entry[1] < max) {
                entry[1]++;
            } else {
                return windowNanos - (now - entry[0]);
            }
            return -1;
        }
    }

    /** Whole seconds, rounded up and at least 1, for a {@code Retry-After} value. */
    public static long seconds(long nanos) {
        return Math.max(1, (nanos + 999_999_999L) / 1_000_000_000L);
    }
}
