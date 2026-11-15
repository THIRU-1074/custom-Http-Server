package in.jolt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import in.jolt.test.JoltTest;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class RateLimitTest {

    private static final long SEC = 1_000_000_000L;

    private static App app(Middleware mw) {
        App app = Jolt.create();
        app.use(mw);
        app.get("/", (req, res) -> res.text("ok"));
        return app;
    }

    private static JoltTest.Call from(App app, String ip) {
        return JoltTest.of(app).get("/").remoteAddress(ip);
    }

    @Test
    void allowsUpToTheLimitThen429WithRetryAfter() {
        App app = app(RateLimit.perIp(2, Duration.ofSeconds(60)));
        from(app, "1.1.1.1").expect(200);
        from(app, "1.1.1.1").expect(200);
        from(app, "1.1.1.1").expect(429).header("Retry-After", "60");
    }

    @Test
    void addressesAreCountedSeparately() {
        App app = app(RateLimit.perIp(1, Duration.ofSeconds(60)));
        from(app, "1.1.1.1").expect(200);
        from(app, "2.2.2.2").expect(200);
        from(app, "1.1.1.1").expect(429);
    }

    @Test
    void windowResetsAndRetryAfterShrinks() {
        AtomicLong now = new AtomicLong();
        App app = app(RateLimit.perIp(1, Duration.ofSeconds(10), now::get, 100));
        from(app, "a").expect(200);
        now.set(4 * SEC + 1);
        from(app, "a").expect(429).header("Retry-After", "6");
        now.set(10 * SEC);
        from(app, "a").expect(200);
    }

    @Test
    void expiredEntriesAreEvictedSoNewKeysFit() {
        AtomicLong now = new AtomicLong();
        App app = app(RateLimit.perIp(1, Duration.ofSeconds(10), now::get, 2));
        from(app, "a").expect(200);
        from(app, "b").expect(200);
        now.set(11 * SEC);
        from(app, "c").expect(200);
        from(app, "d").expect(200);
    }

    @Test
    void newKeysAreRefusedWhenTheTableIsFullOfLiveEntries() {
        AtomicLong now = new AtomicLong();
        App app = app(RateLimit.perIp(5, Duration.ofSeconds(10), now::get, 2));
        from(app, "a").expect(200);
        from(app, "b").expect(200);
        from(app, "c").expect(429);
        from(app, "a").expect(200);
    }

    @Test
    void unknownAddressSharesOneBucket() {
        App app = app(RateLimit.perIp(1, Duration.ofSeconds(60)));
        JoltTest.of(app).get("/").remoteAddress(null).expect(200);
        JoltTest.of(app).get("/").remoteAddress(null).expect(429);
    }

    @Test
    void rejectsBadArguments() {
        assertThrows(
                IllegalArgumentException.class, () -> RateLimit.perIp(0, Duration.ofSeconds(1)));
        assertThrows(IllegalArgumentException.class, () -> RateLimit.perIp(1, Duration.ZERO));
        assertThrows(IllegalArgumentException.class, () -> RateLimit.perIp(1, null));
    }

    @Test
    void requestExposesRemoteAddress() {
        App app = Jolt.create();
        app.get("/", (req, res) -> res.text(String.valueOf(req.remoteAddress())));
        assertEquals("9.9.9.9", JoltTest.of(app).get("/").remoteAddress("9.9.9.9").send().text());
        assertEquals("127.0.0.1", JoltTest.of(app).get("/").send().text());
    }

    @Test
    void serverSetsRemoteAddressFromTheSocket() throws Exception {
        App app = Jolt.create();
        app.get("/", (req, res) -> res.text(req.remoteAddress()));
        try (Server server = app.listen(0)) {
            var client = java.net.http.HttpClient.newHttpClient();
            var request =
                    java.net.http.HttpRequest.newBuilder(
                                    java.net.URI.create("http://127.0.0.1:" + server.port() + "/"))
                            .build();
            var response = client.send(request, java.net.http.HttpResponse.BodyHandlers.ofString());
            assertEquals("127.0.0.1", response.body());
        }
    }
}
