package in.jolt;

import java.time.Duration;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.function.LongSupplier;

final class InMemoryIdempotencyStore implements IdempotencyStore {

    private static final class Entry {
        final String fingerprint;
        Stored response;
        long expiresAt;

        Entry(String fingerprint) {
            this.fingerprint = fingerprint;
        }
    }

    private final int maxEntries;
    private final long ttlNanos;
    private final LongSupplier nanos;
    private final LinkedHashMap<String, Entry> entries = new LinkedHashMap<>(16, 0.75f, true);

    InMemoryIdempotencyStore(int maxEntries, Duration ttl, LongSupplier nanos) {
        if (maxEntries < 1 || ttl == null || ttl.isZero() || ttl.isNegative()) {
            throw new IllegalArgumentException("Need maxEntries >= 1 and a positive ttl");
        }
        this.maxEntries = maxEntries;
        this.ttlNanos = ttl.toNanos();
        this.nanos = nanos;
    }

    @Override
    public synchronized Claim begin(String key, String fingerprint) {
        Entry e = entries.get(key);
        if (e != null && e.response != null && nanos.getAsLong() - e.expiresAt >= 0) {
            entries.remove(key);
            e = null;
        }
        if (e == null) {
            entries.put(key, new Entry(fingerprint));
            trim();
            return Claim.STARTED;
        }
        if (!e.fingerprint.equals(fingerprint)) {
            return Claim.MISMATCH;
        }
        return e.response == null ? Claim.IN_FLIGHT : new Claim(State.COMPLETED, e.response);
    }

    @Override
    public synchronized void complete(String key, Stored response) {
        Entry e = entries.get(key);
        if (e != null && e.response == null) {
            e.response = response;
            e.expiresAt = nanos.getAsLong() + ttlNanos;
        }
    }

    @Override
    public synchronized void release(String key) {
        Entry e = entries.get(key);
        if (e != null && e.response == null) {
            entries.remove(key);
        }
    }

    private void trim() {
        if (entries.size() <= maxEntries) {
            return;
        }
        for (Iterator<Entry> it = entries.values().iterator();
                it.hasNext() && entries.size() > maxEntries; ) {
            if (it.next().response != null) {
                it.remove();
            }
        }
    }
}
