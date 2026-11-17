package in.jolt;

import java.time.Clock;
import java.time.Duration;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;

/**
 * A bounded in-memory cache: least recently used entries are evicted over the maximum size, and
 * each entry expires a fixed TTL after its load finished. Concurrent misses for one key run the
 * loader once and share its result; a failed load is thrown to all of them and not cached.
 */
public final class TtlCache<K, V> {

    private static final class Entry<V> {
        final CompletableFuture<V> value = new CompletableFuture<>();
        final Thread loader = Thread.currentThread();
        long expiresAt = Long.MAX_VALUE;
    }

    private final int maxSize;
    private final long ttlMillis;
    private final Clock clock;
    private final ReentrantLock lock = new ReentrantLock();
    private final LinkedHashMap<K, Entry<V>> map = new LinkedHashMap<>(16, 0.75f, true);
    private final AppMetrics.Counter hits = new AppMetrics.Counter();
    private final AppMetrics.Counter misses = new AppMetrics.Counter();
    private final AppMetrics.Counter evictions = new AppMetrics.Counter();

    private TtlCache(int maxSize, Duration ttl, Clock clock) {
        Objects.requireNonNull(ttl, "ttl");
        Objects.requireNonNull(clock, "clock");
        if (maxSize < 1) {
            throw new IllegalArgumentException("maxSize must be at least 1: " + maxSize);
        }
        if (ttl.isNegative() || ttl.isZero()) {
            throw new IllegalArgumentException("ttl must be positive: " + ttl);
        }
        this.maxSize = maxSize;
        this.ttlMillis =
                ttl.compareTo(Duration.ofDays(365_000)) > 0
                        ? Long.MAX_VALUE
                        : Math.max(1, ttl.toMillis());
        this.clock = clock;
    }

    /** A cache of at most {@code maxSize} entries, each kept for {@code ttl}. */
    public static <K, V> TtlCache<K, V> create(int maxSize, Duration ttl) {
        return new TtlCache<>(maxSize, ttl, Clock.systemUTC());
    }

    /** As {@link #create(int, Duration)}, reading time from {@code clock} (for tests). */
    public static <K, V> TtlCache<K, V> create(int maxSize, Duration ttl, Clock clock) {
        return new TtlCache<>(maxSize, ttl, clock);
    }

    /**
     * Returns the cached value, or loads it with {@code loader} once while other callers for the
     * key wait. The loader runs on the calling thread without a lock held; a throw or null result
     * reaches every waiting caller and is not cached. A loader asking for its own key throws.
     */
    public V get(K key, Function<? super K, ? extends V> loader) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(loader, "loader");
        Entry<V> e;
        boolean mine = false;
        lock.lock();
        try {
            e = map.get(key);
            if (e != null && e.value.isDone() && clock.millis() >= e.expiresAt) {
                map.remove(key);
                evictions.increment();
                e = null;
            }
            if (e == null) {
                e = new Entry<>();
                map.put(key, e);
                mine = true;
                misses.increment();
                evictOverflow();
            } else {
                hits.increment();
            }
        } finally {
            lock.unlock();
        }
        if (mine) {
            return load(key, e, loader);
        }
        if (e.loader == Thread.currentThread() && !e.value.isDone()) {
            throw new IllegalStateException("Loader for " + key + " asked for its own key");
        }
        try {
            return e.value.get();
        } catch (ExecutionException ex) {
            throw rethrow(ex.getCause());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted waiting for " + key, ex);
        }
    }

    private V load(K key, Entry<V> e, Function<? super K, ? extends V> loader) {
        V v;
        try {
            v = Objects.requireNonNull(loader.apply(key), "loader returned null for " + key);
        } catch (Throwable t) {
            remove(key, e);
            e.value.completeExceptionally(t);
            throw rethrow(t);
        }
        lock.lock();
        try {
            long now = clock.millis();
            e.expiresAt = now > Long.MAX_VALUE - ttlMillis ? Long.MAX_VALUE : now + ttlMillis;
        } finally {
            lock.unlock();
        }
        e.value.complete(v);
        return v;
    }

    private void evictOverflow() {
        Iterator<Map.Entry<K, Entry<V>>> it = map.entrySet().iterator();
        while (map.size() > maxSize && it.hasNext()) {
            it.next();
            it.remove();
            evictions.increment();
        }
    }

    private void remove(K key, Entry<V> e) {
        lock.lock();
        try {
            map.remove(key, e);
        } finally {
            lock.unlock();
        }
    }

    private static RuntimeException rethrow(Throwable t) {
        if (t instanceof RuntimeException r) {
            throw r;
        }
        if (t instanceof Error err) {
            throw err;
        }
        throw new CompletionException(t);
    }

    /** Drops {@code key}; a load already running for it still answers its callers. */
    public void invalidate(K key) {
        Objects.requireNonNull(key, "key");
        lock.lock();
        try {
            map.remove(key);
        } finally {
            lock.unlock();
        }
    }

    /** Drops every entry. */
    public void clear() {
        lock.lock();
        try {
            map.clear();
        } finally {
            lock.unlock();
        }
    }

    /** Entries held, including loads in progress and expired entries not yet read again. */
    public int size() {
        lock.lock();
        try {
            return map.size();
        } finally {
            lock.unlock();
        }
    }

    /**
     * Registers {@code <name>_hits_total}, {@code _misses_total} (loads started), {@code
     * _evictions_total} (size or expiry) and the gauge {@code <name>_size} in {@code metrics}.
     */
    public TtlCache<K, V> metrics(AppMetrics metrics, String name) {
        metrics.counter(name + "_hits_total", "Cache hits.", hits);
        metrics.counter(name + "_misses_total", "Cache misses (loads started).", misses);
        metrics.counter(
                name + "_evictions_total", "Cache entries evicted (size or expiry).", evictions);
        metrics.gauge(name + "_size", "Cache entries held.", this::size);
        return this;
    }
}
