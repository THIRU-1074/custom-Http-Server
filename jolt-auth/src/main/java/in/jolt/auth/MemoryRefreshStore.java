package in.jolt.auth;

import java.time.Clock;
import java.time.Instant;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

final class MemoryRefreshStore implements RefreshStore {

    private final int max;
    private final Clock clock;
    private final Map<String, Entry> entries = new LinkedHashMap<>();

    MemoryRefreshStore(int max) {
        this(max, Clock.systemUTC());
    }

    MemoryRefreshStore(int max, Clock clock) {
        this.clock = clock;
        if (max < 1) {
            throw new IllegalArgumentException("maxEntries must be at least 1");
        }
        this.max = max;
    }

    @Override
    public synchronized void save(Entry entry) {
        entries.put(entry.hash(), entry);
        Instant now = clock.instant();
        Iterator<Entry> it = entries.values().iterator();
        while (it.hasNext() && !it.next().expires().isAfter(now)) {
            it.remove();
        }
        while (entries.size() > max) {
            Iterator<String> oldest = entries.keySet().iterator();
            oldest.next();
            oldest.remove();
        }
    }

    @Override
    public synchronized Entry find(String hash) {
        return entries.get(hash);
    }

    @Override
    public synchronized boolean markUsed(String hash) {
        Entry e = entries.get(hash);
        if (e == null || e.used()) {
            return false;
        }
        entries.put(hash, new Entry(hash, e.subject(), e.family(), e.expires(), true));
        return true;
    }

    @Override
    public synchronized void revokeFamily(String family) {
        entries.values().removeIf(e -> e.family().equals(family));
    }

    @Override
    public synchronized Set<String> revokeSubject(String subject) {
        Set<String> families = new HashSet<>();
        entries.values()
                .removeIf(
                        e -> {
                            boolean mine = e.subject().equals(subject);
                            if (mine) {
                                families.add(e.family());
                            }
                            return mine;
                        });
        return families;
    }

    synchronized int size() {
        return entries.size();
    }
}
