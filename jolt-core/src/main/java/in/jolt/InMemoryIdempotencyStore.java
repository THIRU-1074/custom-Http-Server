package in.jolt;

import java.time.Duration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeSet;
import java.util.function.LongSupplier;

final class InMemoryIdempotencyStore implements IdempotencyStore {

    private static final class Entry {
        final String fingerprint;
        final long deadline;
        Stored response;

        Entry(String fingerprint, long deadline) {
            this.fingerprint = fingerprint;
            this.deadline = deadline;
        }
    }

    private static final class Bucket {
        final String scope;
        final LinkedHashMap<String, Entry> done = new LinkedHashMap<>(8, 0.75f, true);
        final Map<String, Entry> running = new HashMap<>();

        Bucket(String scope) {
            this.scope = scope;
        }

        int size() {
            return done.size() + running.size();
        }
    }

    private final int maxEntries;
    private final int perScope;
    private final long ttlNanos;
    private final long inFlightNanos;
    private final LongSupplier nanos;
    private final Map<String, Bucket> buckets = new HashMap<>();
    private final TreeSet<Bucket> bySize =
            new TreeSet<>(
                    (a, b) ->
                            a.done.size() != b.done.size()
                                    ? Integer.compare(b.done.size(), a.done.size())
                                    : a.scope.compareTo(b.scope));
    private int total;

    InMemoryIdempotencyStore(
            int maxEntries,
            int perScope,
            Duration ttl,
            Duration inFlightTimeout,
            LongSupplier nanos) {
        if (maxEntries < 1 || perScope < 1) {
            throw new IllegalArgumentException("Need maxEntries >= 1 and perScope >= 1");
        }
        if (positive(ttl) || positive(inFlightTimeout)) {
            throw new IllegalArgumentException("ttl and inFlightTimeout must be positive");
        }
        this.maxEntries = maxEntries;
        this.perScope = perScope;
        this.ttlNanos = ttl.toNanos();
        this.inFlightNanos = inFlightTimeout.toNanos();
        this.nanos = nanos;
    }

    private static boolean positive(Duration d) {
        return d == null || d.isZero() || d.isNegative();
    }

    @Override
    public synchronized Claim begin(Key key, String fingerprint) {
        long now = nanos.getAsLong();
        Bucket b = buckets.get(key.scope());
        if (b != null) {
            Entry e = b.running.get(key.id());
            if (e != null && now - e.deadline >= 0) {
                b.running.remove(key.id());
                total--;
                e = null;
            }
            if (e == null) {
                e = b.done.get(key.id());
                if (e != null && now - e.deadline >= 0) {
                    evict(b, key.id());
                    e = null;
                }
            }
            if (e != null) {
                if (!e.fingerprint.equals(fingerprint)) {
                    return Claim.mismatch();
                }
                return e.response == null ? Claim.inFlight() : Claim.completed(e.response);
            }
        }
        if (b != null && b.size() >= perScope && !evictOldest(b)) {
            return Claim.full();
        }
        if (total >= maxEntries && !evictFromLargest()) {
            return Claim.full();
        }
        if (b == null || buckets.get(key.scope()) != b) {
            b = buckets.computeIfAbsent(key.scope(), Bucket::new);
            bySize.add(b);
        }
        b.running.put(key.id(), new Entry(fingerprint, now + inFlightNanos));
        total++;
        return Claim.started();
    }

    @Override
    public synchronized void complete(Key key, Stored response) {
        Bucket b = buckets.get(key.scope());
        Entry e = b == null ? null : b.running.get(key.id());
        if (e == null) {
            return;
        }
        b.running.remove(key.id());
        Entry kept = new Entry(e.fingerprint, nanos.getAsLong() + ttlNanos);
        kept.response = response;
        bySize.remove(b);
        b.done.put(key.id(), kept);
        bySize.add(b);
    }

    @Override
    public synchronized void release(Key key) {
        Bucket b = buckets.get(key.scope());
        if (b != null && b.running.remove(key.id()) != null) {
            total--;
            drop(b);
        }
    }

    private boolean evictOldest(Bucket b) {
        if (b.done.isEmpty()) {
            return false;
        }
        evict(b, b.done.keySet().iterator().next());
        return true;
    }

    private boolean evictFromLargest() {
        Bucket largest = bySize.isEmpty() ? null : bySize.first();
        return largest != null && evictOldest(largest);
    }

    private void evict(Bucket b, String id) {
        bySize.remove(b);
        b.done.remove(id);
        total--;
        bySize.add(b);
        drop(b);
    }

    private void drop(Bucket b) {
        if (b.size() == 0) {
            bySize.remove(b);
            buckets.remove(b.scope);
        }
    }
}
