# ADR 0025: In-memory TTL cache with single-flight loading

## Status
Accepted.

## Context
Constitution rule 6 requires an ADR to grow the public API. Catalog reads (cities, movies, show listings) are hot and change rarely, so a booking app caches them and serves them with `res.json(x).etag()` and `res.cache(..)`. A hand-rolled `ConcurrentHashMap` has no bound and no expiry, and when an entry expires under load every concurrent request misses at once and hits the database together (a stampede). `ConcurrentHashMap.computeIfAbsent` avoids the stampede but runs the loader while holding a bin lock, which blocks unrelated keys and, as a `synchronized` block, pins a virtual thread's carrier for the whole load (#63).

## Decision
- `TtlCache<K, V>` in `in.jolt`, created by `TtlCache.create(maxSize, ttl)` or `create(maxSize, ttl, Clock)`. `maxSize >= 1` and a positive `ttl` are required, else `IllegalArgumentException`.
- `get(key, loader)` returns the cached value or loads it. The first caller to miss puts a future in the map and runs the loader on its own thread with no lock held; concurrent callers for that key find the future and wait on it. One `ReentrantLock` guards only map reads and writes, never the loader, so a slow load blocks neither other keys nor carrier threads.
- A loader that throws, or returns null (`NullPointerException`), fails every waiting caller with the same exception, and the entry is removed, so the next call loads again. Failures are never cached. A loader that asks for its own key throws `IllegalStateException` instead of waiting on itself. An interrupted waiter gets `IllegalStateException` with the interrupt flag set; the load carries on for the others.
- Expiry: an entry expires `ttl` after its load finished, measured with `Clock.millis()`, and is removed when next read (lazy expiry, no background thread). Tests pass a controllable `Clock`.
- Eviction: least recently used. The map is an access-ordered `LinkedHashMap`; when an insert takes it over `maxSize` the eldest entries go, whether loaded, expired or still loading. An evicted in-flight load still answers its own waiters but is not stored. Expired entries count towards `maxSize` until read or evicted, so memory stays bounded by `maxSize` without a sweeper.
- `invalidate(key)` and `clear()` drop entries. A load in flight for a dropped key still answers the callers already waiting on it, but its result is not stored, so a later caller never sees a value that was loaded before the invalidation.
- `size()` counts held entries, including loads in progress and expired entries not yet read.
- `cache.metrics(app.metrics(), name)` registers `<name>_hits_total`, `<name>_misses_total` (loads started), `<name>_evictions_total` (removed for size or expiry; `invalidate` and `clear` are not counted) and the gauge `<name>_size` in the app registry (ADR 0021). Counters are kept whether or not they are registered.

## Consequences
- Public API grows by `TtlCache` (two factories, `get`, `invalidate`, `clear`, `size`, `metrics`) at one `llms-extras.txt` line. jolt-core stays dependency-free.
- One lock per cache serialises map access. Each critical section is a hash lookup and a link update, so contention is low for the catalog-sized caches this targets; a sharded or lock-free cache is out of scope.
- The TTL is per cache, not per entry; a cache per freshness class (`catalog` for minutes, `seatHolds` for seconds) covers the use case without a per-call TTL.
- A loader that never returns blocks its waiters, as a slow database would block the request without a cache. Loaders should bound their own time (JDBC query timeout, HTTP client timeout).
