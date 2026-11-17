package in.jolt.auth;

import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

final class MemoryRefreshStore implements RefreshStore {

    static final int DEFAULT_PER_SUBJECT = 20;

    private final int max;
    private final int perSubject;
    private final Clock clock;
    private final Map<String, Entry> live = new LinkedHashMap<>();
    private final Map<String, Entry> used = new LinkedHashMap<>();
    private final Map<String, Set<String>> bySubject = new HashMap<>();
    private final Map<String, Instant> revoked = new HashMap<>();

    MemoryRefreshStore(int max) {
        this(max, DEFAULT_PER_SUBJECT, Clock.systemUTC());
    }

    MemoryRefreshStore(int max, Clock clock) {
        this(max, DEFAULT_PER_SUBJECT, clock);
    }

    MemoryRefreshStore(int max, int perSubject, Clock clock) {
        if (max < 1 || perSubject < 1) {
            throw new IllegalArgumentException("limits must be at least 1");
        }
        this.max = max;
        this.perSubject = perSubject;
        this.clock = clock;
    }

    @Override
    public synchronized void save(Entry entry) {
        purgeExpired();
        if (revoked.containsKey(entry.family())) {
            throw new IllegalStateException("Refresh token family was revoked");
        }
        makeRoom(entry.subject(), 1);
        addLive(entry);
    }

    @Override
    public synchronized boolean rotate(String oldHash, Entry next) {
        purgeExpired();
        Entry old = live.get(oldHash);
        if (old == null
                || revoked.containsKey(old.family())
                || revoked.containsKey(next.family())
                || !old.family().equals(next.family())) {
            return false;
        }
        makeRoom(next.subject(), 0);
        removeLive(oldHash);
        used.put(oldHash, new Entry(oldHash, old.subject(), old.family(), old.expires(), true));
        addLive(next);
        return true;
    }

    @Override
    public synchronized Entry find(String hash) {
        Entry e = live.get(hash);
        return e != null ? e : used.get(hash);
    }

    @Override
    public synchronized void revokeFamily(String family) {
        tombstone(family, drop(e -> e.family().equals(family)));
    }

    @Override
    public synchronized Set<String> revokeSubject(String subject) {
        Map<String, Instant> until = new HashMap<>();
        drop(e -> e.subject().equals(subject), until);
        until.forEach(this::tombstone);
        return new HashSet<>(until.keySet());
    }

    synchronized int size() {
        return live.size() + used.size();
    }

    private Instant drop(Predicate<Entry> test) {
        Map<String, Instant> until = new HashMap<>();
        drop(test, until);
        return until.values().stream().max(Instant::compareTo).orElse(null);
    }

    private void drop(Predicate<Entry> test, Map<String, Instant> until) {
        for (Iterator<Entry> it = live.values().iterator(); it.hasNext(); ) {
            Entry e = it.next();
            if (test.test(e)) {
                it.remove();
                unindex(e);
                until.merge(e.family(), e.expires(), (x, y) -> x.isAfter(y) ? x : y);
            }
        }
        for (Iterator<Entry> it = used.values().iterator(); it.hasNext(); ) {
            Entry e = it.next();
            if (test.test(e)) {
                it.remove();
                until.merge(e.family(), e.expires(), (x, y) -> x.isAfter(y) ? x : y);
            }
        }
    }

    private void tombstone(String family, Instant until) {
        if (until != null) {
            revoked.merge(family, until, (x, y) -> x.isAfter(y) ? x : y);
        }
    }

    private void purgeExpired() {
        Instant now = clock.instant();
        for (Iterator<Entry> it = live.values().iterator(); it.hasNext(); ) {
            Entry e = it.next();
            if (e.expires().isAfter(now)) {
                break;
            }
            it.remove();
            unindex(e);
        }
        for (Iterator<Entry> it = used.values().iterator(); it.hasNext(); ) {
            if (it.next().expires().isAfter(now)) {
                break;
            }
            it.remove();
        }
        if (!revoked.isEmpty()) {
            revoked.values().removeIf(until -> !until.isAfter(now));
        }
    }

    private void makeRoom(String subject, int extraLive) {
        Set<String> mine = bySubject.get(subject);
        while (mine != null && !mine.isEmpty() && mine.size() + extraLive > perSubject) {
            removeLive(mine.iterator().next());
            mine = bySubject.get(subject);
        }
        while (live.size() + used.size() + 1 > max) {
            if (used.isEmpty()) {
                throw new IllegalStateException("Refresh token store is full");
            }
            Iterator<String> oldest = used.keySet().iterator();
            oldest.next();
            oldest.remove();
        }
    }

    private void addLive(Entry e) {
        live.put(e.hash(), e);
        bySubject.computeIfAbsent(e.subject(), k -> new LinkedHashSet<>()).add(e.hash());
    }

    private void removeLive(String hash) {
        Entry e = live.remove(hash);
        if (e != null) {
            unindex(e);
        }
    }

    private void unindex(Entry e) {
        Set<String> mine = bySubject.get(e.subject());
        if (mine != null) {
            mine.remove(e.hash());
            if (mine.isEmpty()) {
                bySubject.remove(e.subject());
            }
        }
    }
}
