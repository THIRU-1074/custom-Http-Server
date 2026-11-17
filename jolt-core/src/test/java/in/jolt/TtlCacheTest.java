package in.jolt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;

class TtlCacheTest {

    private static final class TestClock extends Clock {
        private final AtomicLong millis = new AtomicLong(1_000_000);

        void advance(Duration d) {
            millis.addAndGet(d.toMillis());
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public long millis() {
            return millis.get();
        }

        @Override
        public Instant instant() {
            return Instant.ofEpochMilli(millis());
        }
    }

    private static void await(BooleanSupplier condition, String what) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!condition.getAsBoolean()) {
            assertTrue(System.nanoTime() < deadline, "timed out waiting for " + what);
            Thread.sleep(2);
        }
    }

    private final TestClock clock = new TestClock();
    private final AtomicInteger loads = new AtomicInteger();

    private String load(String key) {
        return key + "#" + loads.incrementAndGet();
    }

    @Test
    void loadsOnceAndServesLaterCallsFromTheCache() {
        TtlCache<String, String> cache = TtlCache.create(10, Duration.ofMinutes(1), clock);
        assertEquals("a#1", cache.get("a", this::load));
        assertEquals("a#1", cache.get("a", this::load));
        assertEquals("b#2", cache.get("b", this::load));
        assertEquals(2, loads.get());
        assertEquals(2, cache.size());
    }

    @Test
    void anEntryExpiresWhenItsTtlHasPassed() {
        TtlCache<String, String> cache = TtlCache.create(10, Duration.ofSeconds(30), clock);
        cache.get("a", this::load);
        clock.advance(Duration.ofSeconds(10));
        cache.get("b", this::load);
        clock.advance(Duration.ofMillis(19_999));
        assertEquals("a#1", cache.get("a", this::load));
        clock.advance(Duration.ofMillis(1));
        assertEquals("a#3", cache.get("a", this::load));
        assertEquals("b#2", cache.get("b", this::load));
        clock.advance(Duration.ofSeconds(10));
        assertEquals("b#4", cache.get("b", this::load));
    }

    @Test
    void theTtlCountsFromWhenTheLoadFinished() {
        TtlCache<String, String> cache = TtlCache.create(10, Duration.ofSeconds(30), clock);
        cache.get(
                "a",
                k -> {
                    clock.advance(Duration.ofSeconds(20));
                    return load(k);
                });
        clock.advance(Duration.ofSeconds(29));
        assertEquals("a#1", cache.get("a", this::load));
    }

    @Test
    void theLeastRecentlyUsedEntryIsEvictedOverTheMaximumSize() {
        TtlCache<String, String> cache = TtlCache.create(2, Duration.ofMinutes(1), clock);
        cache.get("a", this::load);
        cache.get("b", this::load);
        cache.get("a", this::load);
        cache.get("c", this::load);
        assertEquals(2, cache.size());
        assertEquals("a#1", cache.get("a", this::load));
        assertEquals("c#3", cache.get("c", this::load));
        assertEquals("b#4", cache.get("b", this::load));
        assertEquals("a#5", cache.get("a", this::load));
    }

    @Test
    void aLoaderFailureIsThrownAndNotCached() {
        TtlCache<String, String> cache = TtlCache.create(10, Duration.ofMinutes(1), clock);
        IllegalStateException boom = new IllegalStateException("db down");
        IllegalStateException thrown =
                assertThrows(
                        IllegalStateException.class,
                        () ->
                                cache.get(
                                        "a",
                                        k -> {
                                            throw boom;
                                        }));
        assertSame(boom, thrown);
        assertEquals(0, cache.size());
        assertEquals("a#1", cache.get("a", this::load));
    }

    @Test
    void aNullValueIsRejectedAndNotCached() {
        TtlCache<String, String> cache = TtlCache.create(10, Duration.ofMinutes(1), clock);
        assertThrows(NullPointerException.class, () -> cache.get("a", k -> null));
        assertEquals(0, cache.size());
        assertEquals("a#1", cache.get("a", this::load));
    }

    @Test
    void invalidateAndClearDropEntries() {
        TtlCache<String, String> cache = TtlCache.create(10, Duration.ofMinutes(1), clock);
        cache.get("a", this::load);
        cache.get("b", this::load);
        cache.invalidate("a");
        cache.invalidate("missing");
        assertEquals(1, cache.size());
        assertEquals("a#3", cache.get("a", this::load));
        assertEquals("b#2", cache.get("b", this::load));
        cache.clear();
        assertEquals(0, cache.size());
        assertEquals("b#4", cache.get("b", this::load));
    }

    @Test
    void rejectsInvalidArguments() {
        assertThrows(
                IllegalArgumentException.class, () -> TtlCache.create(0, Duration.ofSeconds(1)));
        assertThrows(IllegalArgumentException.class, () -> TtlCache.create(1, Duration.ZERO));
        assertThrows(
                IllegalArgumentException.class, () -> TtlCache.create(1, Duration.ofSeconds(-1)));
        assertThrows(NullPointerException.class, () -> TtlCache.create(1, null));
        assertThrows(
                NullPointerException.class, () -> TtlCache.create(1, Duration.ofSeconds(1), null));
        TtlCache<String, String> cache = TtlCache.create(1, Duration.ofSeconds(1));
        assertThrows(NullPointerException.class, () -> cache.get(null, this::load));
        assertThrows(NullPointerException.class, () -> cache.get("a", null));
    }

    @Test
    void aLoaderThatAsksForItsOwnKeyFailsInsteadOfDeadlocking() {
        TtlCache<String, String> cache = TtlCache.create(10, Duration.ofMinutes(1), clock);
        assertThrows(
                IllegalStateException.class, () -> cache.get("a", k -> cache.get("a", this::load)));
        assertEquals("a#1", cache.get("a", this::load));
    }

    @Test
    void concurrentMissesForOneKeyRunTheLoaderOnce() throws Exception {
        TtlCache<String, String> cache = TtlCache.create(10, Duration.ofMinutes(1), clock);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        int callers = 1000;
        CountDownLatch started = new CountDownLatch(callers);
        List<Future<String>> results = new ArrayList<>();
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < callers; i++) {
                results.add(
                        pool.submit(
                                () -> {
                                    started.countDown();
                                    return cache.get(
                                            "movies",
                                            k -> {
                                                entered.countDown();
                                                try {
                                                    release.await();
                                                } catch (InterruptedException e) {
                                                    throw new IllegalStateException(e);
                                                }
                                                return load(k);
                                            });
                                }));
            }
            assertTrue(started.await(5, TimeUnit.SECONDS));
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            release.countDown();
            for (Future<String> f : results) {
                assertEquals("movies#1", f.get(5, TimeUnit.SECONDS));
            }
        }
        assertEquals(1, loads.get());
    }

    @Test
    void concurrentWaitersAllSeeTheFailureAndTheNextCallLoadsAgain() throws Exception {
        TtlCache<String, String> cache = TtlCache.create(10, Duration.ofMinutes(1), clock);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger attempts = new AtomicInteger();
        int callers = 200;
        List<Future<String>> results = new ArrayList<>();
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < callers; i++) {
                results.add(
                        pool.submit(
                                () ->
                                        cache.get(
                                                "movies",
                                                k -> {
                                                    attempts.incrementAndGet();
                                                    try {
                                                        release.await();
                                                    } catch (InterruptedException e) {
                                                        throw new IllegalStateException(e);
                                                    }
                                                    throw new IllegalStateException("db down");
                                                })));
            }
            await(() -> attempts.get() == 1, "the first load to start");
            Thread.sleep(50);
            release.countDown();
            int failed = 0;
            for (Future<String> f : results) {
                try {
                    f.get(5, TimeUnit.SECONDS);
                } catch (java.util.concurrent.ExecutionException e) {
                    assertTrue(e.getCause() instanceof IllegalStateException);
                    failed++;
                }
            }
            assertEquals(callers, failed);
        }
        assertTrue(attempts.get() >= 1);
        assertEquals("movies#1", cache.get("movies", this::load));
    }

    @Test
    void aSlowLoadDoesNotBlockOtherKeys() throws Exception {
        TtlCache<String, String> cache = TtlCache.create(10, Duration.ofMinutes(1), clock);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Thread slow =
                Thread.ofVirtual()
                        .start(
                                () ->
                                        cache.get(
                                                "slow",
                                                k -> {
                                                    entered.countDown();
                                                    try {
                                                        release.await();
                                                    } catch (InterruptedException e) {
                                                        throw new IllegalStateException(e);
                                                    }
                                                    return "s";
                                                }));
        assertTrue(entered.await(5, TimeUnit.SECONDS));
        assertEquals("fast#1", cache.get("fast", this::load));
        cache.invalidate("other");
        assertEquals(2, cache.size());
        release.countDown();
        slow.join(5000);
        assertEquals("s", cache.get("slow", this::load));
    }

    @Test
    void aLoadInvalidatedWhileRunningIsReturnedButNotStored() throws Exception {
        TtlCache<String, String> cache = TtlCache.create(10, Duration.ofMinutes(1), clock);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<String> first =
                    pool.submit(
                            () ->
                                    cache.get(
                                            "a",
                                            k -> {
                                                entered.countDown();
                                                try {
                                                    release.await();
                                                } catch (InterruptedException e) {
                                                    throw new IllegalStateException(e);
                                                }
                                                return "stale";
                                            }));
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            cache.invalidate("a");
            release.countDown();
            assertEquals("stale", first.get(5, TimeUnit.SECONDS));
        }
        assertEquals("a#1", cache.get("a", this::load));
    }

    @Test
    void metricsCountHitsMissesAndEvictions() {
        App app = Jolt.create();
        TtlCache<String, String> cache =
                TtlCache.<String, String>create(2, Duration.ofSeconds(30), clock)
                        .metrics(app.metrics(), "catalog");
        cache.get("a", this::load);
        cache.get("a", this::load);
        cache.get("b", this::load);
        cache.get("c", this::load);
        clock.advance(Duration.ofSeconds(30));
        cache.get("c", this::load);
        cache.invalidate("c");
        String text = app.metrics().render();
        assertTrue(
                text.contains("# TYPE catalog_hits_total counter\ncatalog_hits_total 1\n"), text);
        assertTrue(text.contains("catalog_misses_total 4\n"), text);
        assertTrue(text.contains("catalog_evictions_total 2\n"), text);
        assertTrue(text.contains("# TYPE catalog_size gauge\ncatalog_size 1\n"), text);
    }
}
