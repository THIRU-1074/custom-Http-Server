package in.jolt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import in.jolt.IdempotencyStore.Claim;
import in.jolt.IdempotencyStore.Key;
import in.jolt.IdempotencyStore.State;
import in.jolt.IdempotencyStore.Stored;
import in.jolt.test.JoltTest;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.GZIPInputStream;
import org.junit.jupiter.api.Test;

class IdempotencyHardeningTest {

    private final AtomicInteger runs = new AtomicInteger();

    private App app(Middleware... before) {
        App app = Jolt.create();
        for (Middleware m : before) {
            app.use(m);
        }
        return app;
    }

    private static JoltTest.Call post(App app, String path) {
        return JoltTest.of(app).post(path).header("Idempotency-Key", "k");
    }

    private static Stored stored(int status) {
        return new Stored(status, List.of(), new byte[0]);
    }

    @Test
    void responsesAboutTheCallerAreNotStored() {
        for (int status : new int[] {401, 403, 407, 408, 425, 429}) {
            runs.set(0);
            App app = app(Idempotency.keys());
            app.post(
                    "/p",
                    (req, res) -> {
                        res.status(runs.incrementAndGet() == 1 ? status : 201).text("x");
                    });
            post(app, "/p").text("a").expect(status);
            post(app, "/p").text("a").expect(201);
            assertEquals(2, runs.get(), "status " + status);
        }
    }

    @Test
    void aStored422IsStillReplayed() {
        App app = app(Idempotency.keys());
        app.post("/p", (req, res) -> res.status(422).text("bad" + runs.incrementAndGet()));
        post(app, "/p").text("a").expect(422);
        post(app, "/p").text("a").expect(422).header("Idempotency-Replayed", "true");
        assertEquals(1, runs.get());
    }

    @Test
    void scopeUsesAStringOrNumberUser() {
        App app =
                app(
                        (req, res, next) -> {
                            req.attr(
                                    "user",
                                    req.header("X-Id").equals("n")
                                            ? (Object) 7
                                            : req.header("X-Id"));
                            next.run();
                        });
        app.use(Idempotency.keys());
        app.post("/p", (req, res) -> res.status(201).text("n" + runs.incrementAndGet()));
        post(app, "/p").header("X-Id", "a").expect(201);
        assertEquals(
                "true",
                post(app, "/p").header("X-Id", "a").expect(201).header("Idempotency-Replayed"));
        assertNull(post(app, "/p").header("X-Id", "b").expect(201).header("Idempotency-Replayed"));
        post(app, "/p").header("X-Id", "n").expect(201);
        post(app, "/p").header("X-Id", "n").expect(201).header("Idempotency-Replayed", "true");
    }

    @Test
    void aNonTextUserFallsBackToAuthorizationThenAddress() {
        App app =
                app(
                        (req, res, next) -> {
                            req.attr("user", new Object());
                            next.run();
                        });
        app.use(Idempotency.keys());
        app.post("/p", (req, res) -> res.status(201).text("n" + runs.incrementAndGet()));
        post(app, "/p").header("Authorization", "Bearer a").expect(201);
        post(app, "/p")
                .header("Authorization", "Bearer a")
                .remoteAddress("10.9.9.9")
                .expect(201)
                .header("Idempotency-Replayed", "true");
        assertNull(
                post(app, "/p")
                        .header("Authorization", "Bearer b")
                        .expect(201)
                        .header("Idempotency-Replayed"));
        post(app, "/p").expect(201);
        post(app, "/p").expect(201).header("Idempotency-Replayed", "true");
        assertNull(
                post(app, "/p")
                        .remoteAddress("10.0.0.5")
                        .expect(201)
                        .header("Idempotency-Replayed"));
        assertEquals(4, runs.get());
    }

    @Test
    void noDerivableScopeIs400() {
        App app = app(Idempotency.keys());
        app.post("/p", (req, res) -> res.text("x"));
        post(app, "/p")
                .remoteAddress(null)
                .expect(400)
                .body("Idempotency needs an authenticated or addressable client");
    }

    @Test
    void customScopeFunctionIsUsed() {
        App app = app(Idempotency.keys().scope(req -> req.header("X-Tenant")));
        app.post("/p", (req, res) -> res.status(201).text("n" + runs.incrementAndGet()));
        post(app, "/p").header("X-Tenant", "t1").expect(201);
        post(app, "/p").header("X-Tenant", "t1").expect(201).header("Idempotency-Replayed", "true");
        post(app, "/p").header("X-Tenant", "t2").expect(201);
        post(app, "/p").expect(400);
        assertEquals(2, runs.get());
    }

    @Test
    void replayKeepsEncodingAndCustomHeadersButNotConnectionOnes() throws IOException {
        App app = app(Idempotency.keys(), Compress.gzip(10));
        app.post(
                "/p",
                (req, res) ->
                        res.header("X-Custom", "1")
                                .header("Vary", "Accept")
                                .header("Cache-Control", "no-store")
                                .header("Set-Cookie", "a=b")
                                .header("Date", "x")
                                .json(Map.of("text", "x".repeat(500))));
        post(app, "/p").header("Accept-Encoding", "gzip").text("a").expect(200);
        var replay = post(app, "/p").header("Accept-Encoding", "gzip").text("a").expect(200);
        replay.header("Idempotency-Replayed", "true")
                .header("Content-Encoding", "gzip")
                .header("X-Custom", "1")
                .header("Cache-Control", "no-store");
        assertNull(replay.header("Set-Cookie"));
        assertNull(replay.header("Date"));
        try (var in = new GZIPInputStream(new ByteArrayInputStream(replay.bytes()))) {
            assertTrue(new String(in.readAllBytes(), StandardCharsets.UTF_8).contains("xxxx"));
        }
    }

    @Test
    void replayInventsNeitherContentTypeNorBody() {
        App app = app(Idempotency.keys());
        app.post("/p", (req, res) -> res.status(204));
        app.post("/r", (req, res) -> res.status(303).header("Location", "/z"));
        post(app, "/p").expect(204);
        var empty = post(app, "/p").expect(204);
        empty.header("Idempotency-Replayed", "true");
        assertNull(empty.header("Content-Type"));
        assertEquals(0, empty.bytes().length);
        post(app, "/r").header("Idempotency-Key", "k2").expect(303);
        var redirect = post(app, "/r").header("Idempotency-Key", "k2").expect(303);
        redirect.header("Location", "/z");
        assertNull(redirect.header("Content-Type"));
        assertEquals(0, redirect.bytes().length);
    }

    @Test
    void contentTypeIsPartOfTheFingerprint() {
        App app = app(Idempotency.keys());
        app.post("/p", (req, res) -> res.status(201).text("ok"));
        post(app, "/p").header("Content-Type", "text/plain").text("a").expect(201);
        post(app, "/p").header("Content-Type", "application/json").body("a".getBytes()).expect(422);
    }

    @Test
    void ttlAndCustomStoreCannotBeCombined() {
        IdempotencyStore custom = IdempotencyStore.inMemory();
        var a =
                assertThrows(
                        IllegalStateException.class,
                        () -> Idempotency.keys().store(custom).ttl(Duration.ofHours(1)));
        assertEquals(
                "ttl applies to the default store only; configure it on your store",
                a.getMessage());
        assertThrows(
                IllegalStateException.class,
                () -> Idempotency.keys().ttl(Duration.ofHours(1)).store(custom));
        assertThrows(
                IllegalStateException.class,
                () -> Idempotency.keys().store(custom).inFlightTimeout(Duration.ofMinutes(1)));
    }

    private static InMemoryIdempotencyStore store(int max, int perScope, AtomicLong now) {
        return new InMemoryIdempotencyStore(
                max, perScope, Duration.ofHours(1), Duration.ofMinutes(5), now::get);
    }

    @Test
    void aHungRequestStopsBlockingRetries() {
        AtomicLong now = new AtomicLong();
        var s = store(10, 10, now);
        Key k = new Key("s", "k");
        assertEquals(State.STARTED, s.begin(k, "f").state());
        now.addAndGet(Duration.ofMinutes(4).toNanos());
        assertEquals(State.IN_FLIGHT, s.begin(k, "f").state());
        now.addAndGet(Duration.ofMinutes(2).toNanos());
        assertEquals(State.STARTED, s.begin(k, "f").state());
    }

    @Test
    void oneScopeFloodingDoesNotEvictAnother() {
        AtomicLong now = new AtomicLong();
        var s = store(100, 3, now);
        Key b = new Key("B", "keep");
        s.begin(b, "f");
        s.complete(b, stored(201));
        for (int i = 0; i < 20; i++) {
            Key a = new Key("A", "k" + i);
            assertEquals(State.STARTED, s.begin(a, "f").state());
            s.complete(a, stored(201));
        }
        assertEquals(State.COMPLETED, s.begin(b, "f").state());
        assertEquals(State.STARTED, s.begin(new Key("A", "k0"), "f").state());
        assertEquals(State.COMPLETED, s.begin(new Key("A", "k19"), "f").state());
    }

    @Test
    void globalCapEvictsFromTheLargestScope() {
        AtomicLong now = new AtomicLong();
        var s = store(4, 10, now);
        for (String id : new String[] {"b1", "a1", "a2", "a3"}) {
            Key k = new Key(id.substring(0, 1), id);
            s.begin(k, "f");
            s.complete(k, stored(201));
        }
        Key c = new Key("C", "c1");
        assertEquals(State.STARTED, s.begin(c, "f").state());
        assertEquals(State.COMPLETED, s.begin(new Key("b", "b1"), "f").state());
        assertEquals(State.STARTED, s.begin(new Key("a", "a1"), "f").state());
    }

    @Test
    void aStoreFullOfRunningRequestsAnswers503() {
        AtomicLong now = new AtomicLong();
        var s = store(2, 10, now);
        s.begin(new Key("a", "1"), "f");
        s.begin(new Key("b", "2"), "f");
        assertEquals(State.FULL, s.begin(new Key("c", "3"), "f").state());
        App app = Jolt.create();
        app.use(Idempotency.keys().store(s));
        app.post("/p", (req, res) -> res.text("x"));
        post(app, "/p").expect(503).header("Retry-After", "1");
    }

    @Test
    void claimFactoriesAreUsableByExternalStores() {
        Stored r = stored(200);
        assertEquals(State.COMPLETED, Claim.completed(r).state());
        assertEquals(r, Claim.completed(r).response());
        assertEquals(State.STARTED, Claim.started().state());
        assertEquals(State.IN_FLIGHT, Claim.inFlight().state());
        assertEquals(State.MISMATCH, Claim.mismatch().state());
        assertEquals(State.FULL, Claim.full().state());
    }
}
