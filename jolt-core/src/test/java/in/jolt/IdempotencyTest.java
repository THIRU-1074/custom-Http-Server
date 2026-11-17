package in.jolt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import in.jolt.test.JoltTest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class IdempotencyTest {

    private final AtomicInteger runs = new AtomicInteger();

    private App app(Idempotency idem) {
        App app = Jolt.create();
        app.use(
                (req, res, next) -> {
                    String user = req.header("X-User");
                    if (user != null) {
                        req.attr("user", user);
                    }
                    next.run();
                });
        app.use(idem);
        Handler create =
                (req, res) -> {
                    int n = runs.incrementAndGet();
                    res.status(201)
                            .header("Location", "/orders/" + n)
                            .header("Set-Cookie", "a=b")
                            .json(java.util.Map.of("order", n));
                };
        app.post("/orders", create);
        app.put("/orders", create);
        app.get("/orders", (req, res) -> res.text("n=" + runs.incrementAndGet()));
        app.post(
                "/boom",
                (req, res) -> {
                    if (runs.incrementAndGet() == 1) {
                        throw new IllegalStateException("fail");
                    }
                    res.status(201).text("ok");
                });
        app.post(
                "/unavailable",
                (req, res) -> {
                    res.status(runs.incrementAndGet() == 1 ? 503 : 201).text("x");
                });
        return app;
    }

    private static JoltTest.Call post(App app, String key) {
        return JoltTest.of(app).post("/orders").header("Idempotency-Key", key);
    }

    @Test
    void repeatReplaysTheStoredResponse() {
        App app = app(Idempotency.keys());
        assertNull(post(app, "k1").text("a").expect(201).header("Idempotency-Replayed"));
        var replay = post(app, "k1").text("a").expect(201);
        replay.header("Idempotency-Replayed", "true")
                .header("Location", "/orders/1")
                .bodyContains("\"order\":1");
        assertNull(replay.header("Set-Cookie"));
        assertEquals(1, runs.get());
    }

    @Test
    void differentFingerprintGets422() {
        App app = app(Idempotency.keys());
        post(app, "k1").text("a").expect(201);
        post(app, "k1").text("b").expect(422);
        post(app, "k1").text("a").remoteAddress("127.0.0.1").expect(201);
        JoltTest.of(app).put("/orders").header("Idempotency-Key", "k1").text("a").expect(422);
        JoltTest.of(app).post("/orders?x=1").header("Idempotency-Key", "k1").text("a").expect(422);
        assertEquals(1, runs.get());
    }

    @Test
    void repeatWhileRunningGets409WithRetryAfter() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        App app = Jolt.create();
        app.use(Idempotency.keys());
        app.post(
                "/slow",
                (req, res) -> {
                    runs.incrementAndGet();
                    entered.countDown();
                    try {
                        release.await(5, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    res.status(201).text("done");
                });
        Thread first =
                Thread.ofVirtual()
                        .start(
                                () ->
                                        JoltTest.of(app)
                                                .post("/slow")
                                                .header("Idempotency-Key", "k")
                                                .expect(201));
        assertTrue(entered.await(5, TimeUnit.SECONDS));
        var busy = JoltTest.of(app).post("/slow").header("Idempotency-Key", "k").expect(409);
        assertEquals("1", busy.header("Retry-After"));
        release.countDown();
        first.join(5000);
        JoltTest.of(app)
                .post("/slow")
                .header("Idempotency-Key", "k")
                .expect(201)
                .header("Idempotency-Replayed", "true");
        assertEquals(1, runs.get());
    }

    @Test
    void serverErrorsAndThrownHandlersAreNotStored() {
        App app = app(Idempotency.keys());
        JoltTest.of(app).post("/boom").header("Idempotency-Key", "k").expect(500);
        JoltTest.of(app).post("/boom").header("Idempotency-Key", "k").expect(201);
        runs.set(0);
        JoltTest.of(app).post("/unavailable").header("Idempotency-Key", "u").expect(503);
        assertNull(
                JoltTest.of(app)
                        .post("/unavailable")
                        .header("Idempotency-Key", "u")
                        .expect(201)
                        .header("Idempotency-Replayed"));
        assertEquals(2, runs.get());
    }

    @Test
    void keysAreScopedPerUserElseAddress() {
        App app = app(Idempotency.keys());
        post(app, "k").header("X-User", "alice").expect(201);
        assertNull(
                post(app, "k").header("X-User", "bob").expect(201).header("Idempotency-Replayed"));
        post(app, "k").header("X-User", "alice").expect(201).header("Idempotency-Replayed", "true");
        assertNull(
                post(app, "k")
                        .remoteAddress("10.0.0.1")
                        .expect(201)
                        .header("Idempotency-Replayed"));
        post(app, "k").remoteAddress("10.0.0.1").expect(201).header("Idempotency-Replayed", "true");
        assertNull(
                post(app, "k")
                        .remoteAddress("10.0.0.2")
                        .expect(201)
                        .header("Idempotency-Replayed"));
        assertEquals(4, runs.get());
    }

    @Test
    void badKeyFormatGets400() {
        App app = app(Idempotency.keys());
        post(app, "").expect(400);
        post(app, "has space").expect(400);
        post(app, "café").expect(400);
        post(app, "x".repeat(129)).expect(400);
        post(app, "x".repeat(128)).expect(201);
        assertEquals(1, runs.get());
    }

    @Test
    void requiredModeRejectsAMissingKey() {
        App app = app(Idempotency.keys().required());
        JoltTest.of(app).post("/orders").expect(400).bodyContains("Idempotency-Key");
        JoltTest.of(app).get("/orders").expect(200);
        post(app, "k").expect(201);
    }

    @Test
    void withoutTheHeaderRequestsPassThrough() {
        App app = app(Idempotency.keys());
        JoltTest.of(app).post("/orders").expect(201);
        JoltTest.of(app).post("/orders").expect(201);
        assertEquals(2, runs.get());
    }

    @Test
    void getAndUnlistedMethodsAreUntouched() {
        App app = app(Idempotency.keys());
        JoltTest.of(app).get("/orders").header("Idempotency-Key", "k").expect(200);
        assertNull(
                JoltTest.of(app)
                        .get("/orders")
                        .header("Idempotency-Key", "k")
                        .expect(200)
                        .header("Idempotency-Replayed"));
        assertEquals(2, runs.get());
        App postOnly = app(Idempotency.keys().methods("POST"));
        JoltTest.of(postOnly).put("/orders").header("Idempotency-Key", "k").expect(201);
        JoltTest.of(postOnly).put("/orders").header("Idempotency-Key", "k").expect(201);
        assertEquals(4, runs.get());
    }

    @Test
    void entriesExpireAfterTheTtl() {
        AtomicLong now = new AtomicLong();
        App app =
                app(
                        Idempotency.keys()
                                .store(
                                        new InMemoryIdempotencyStore(
                                                10, Duration.ofHours(1), now::get)));
        post(app, "k").expect(201);
        now.addAndGet(Duration.ofMinutes(59).toNanos());
        post(app, "k").expect(201).header("Idempotency-Replayed", "true");
        now.addAndGet(Duration.ofMinutes(2).toNanos());
        assertNull(post(app, "k").expect(201).header("Idempotency-Replayed"));
        assertEquals(2, runs.get());
    }

    @Test
    void storeIsBoundedAndEvictsTheLeastRecentlyUsed() {
        AtomicLong now = new AtomicLong();
        App app =
                app(
                        Idempotency.keys()
                                .store(
                                        new InMemoryIdempotencyStore(
                                                2, Duration.ofHours(1), now::get)));
        post(app, "a").expect(201);
        post(app, "b").expect(201);
        post(app, "a").expect(201).header("Idempotency-Replayed", "true");
        post(app, "c").expect(201);
        post(app, "a").expect(201).header("Idempotency-Replayed", "true");
        assertNull(post(app, "b").expect(201).header("Idempotency-Replayed"));
        assertEquals(4, runs.get());
    }

    @Test
    void oversizedResponsesAreNotStored() {
        App app = app(Idempotency.keys().maxBodyBytes(4));
        post(app, "k").expect(201);
        assertNull(post(app, "k").expect(201).header("Idempotency-Replayed"));
        assertEquals(2, runs.get());
    }

    @Test
    void simultaneousDuplicatesRunTheHandlerOnce() throws Exception {
        App app = app(Idempotency.keys());
        int n = 32;
        CountDownLatch start = new CountDownLatch(1);
        List<Thread> threads = new ArrayList<>();
        AtomicInteger created = new AtomicInteger();
        AtomicInteger busy = new AtomicInteger();
        AtomicInteger replayed = new AtomicInteger();
        for (int i = 0; i < n; i++) {
            threads.add(
                    Thread.ofVirtual()
                            .start(
                                    () -> {
                                        try {
                                            start.await();
                                        } catch (InterruptedException e) {
                                            return;
                                        }
                                        var r = post(app, "same").text("a").send();
                                        if ("true".equals(r.header("Idempotency-Replayed"))) {
                                            replayed.incrementAndGet();
                                        } else if (r.status() == 409) {
                                            busy.incrementAndGet();
                                        } else if (r.status() == 201) {
                                            created.incrementAndGet();
                                        }
                                    }));
        }
        start.countDown();
        for (Thread t : threads) {
            t.join(10000);
        }
        assertEquals(1, runs.get());
        assertEquals(1, created.get());
        assertEquals(n, created.get() + busy.get() + replayed.get());
    }

    @Test
    void invalidConfigurationIsRejected() {
        for (Runnable bad :
                List.<Runnable>of(
                        () -> Idempotency.keys().ttl(Duration.ZERO),
                        () -> Idempotency.keys().store(null),
                        () -> Idempotency.keys().maxBodyBytes(-1),
                        () -> Idempotency.keys().methods(),
                        () -> IdempotencyStore.inMemory(0, Duration.ofHours(1)))) {
            try {
                bad.run();
                throw new AssertionError("expected IllegalArgumentException");
            } catch (IllegalArgumentException expected) {
                // rejected as intended
            }
        }
    }
}
