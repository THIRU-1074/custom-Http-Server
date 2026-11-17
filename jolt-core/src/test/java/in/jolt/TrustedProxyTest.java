package in.jolt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import in.jolt.test.JoltTest;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class TrustedProxyTest {

    private static App app(JoltConfig config) {
        App app = Jolt.create(config);
        app.get(
                "/",
                (req, res) ->
                        res.text(req.remoteAddress() + "|" + req.scheme() + "|" + req.host()));
        return app;
    }

    private static String seen(JoltConfig config, String peer, String... headers) {
        var call = JoltTest.of(app(config)).get("/").remoteAddress(peer);
        for (int i = 0; i < headers.length; i += 2) {
            call.header(headers[i], headers[i + 1]);
        }
        return call.expect(200).text();
    }

    private static final JoltConfig TRUST_10 = JoltConfig.defaults().trustProxy("10.0.0.0/8");

    @Test
    void defaultTrustsNobody() {
        assertEquals(
                "8.8.8.8|http|example.com",
                seen(
                        JoltConfig.defaults(),
                        "8.8.8.8",
                        "Host",
                        "example.com",
                        "X-Forwarded-For",
                        "1.1.1.1",
                        "X-Forwarded-Proto",
                        "https",
                        "X-Forwarded-Host",
                        "evil.test"));
    }

    @Test
    void untrustedPeerCannotSpoofEvenWhenAProxyIsConfigured() {
        assertEquals(
                "8.8.8.8|http|example.com",
                seen(
                        TRUST_10,
                        "8.8.8.8",
                        "Host",
                        "example.com",
                        "X-Forwarded-For",
                        "1.1.1.1",
                        "X-Forwarded-Proto",
                        "https",
                        "X-Forwarded-Host",
                        "evil.test"));
    }

    @Test
    void trustedPeerSetsAddressSchemeAndHost() {
        assertEquals(
                "1.2.3.4|https|app.example.com",
                seen(
                        TRUST_10,
                        "10.1.2.3",
                        "Host",
                        "internal:8080",
                        "X-Forwarded-For",
                        "1.2.3.4",
                        "X-Forwarded-Proto",
                        "HTTPS",
                        "X-Forwarded-Host",
                        "app.example.com"));
    }

    @Test
    void trustedPeerWithoutForwardedHeadersKeepsItsOwnValues() {
        assertEquals("10.1.2.3|http|internal", seen(TRUST_10, "10.1.2.3", "Host", "internal"));
    }

    @Test
    void rightmostUntrustedEntryWinsSoLeftEntriesCannotBeForged() {
        assertEquals(
                "1.2.3.4|http|null",
                seen(TRUST_10, "10.0.0.1", "X-Forwarded-For", "9.9.9.9, 1.2.3.4, 10.0.0.2"));
    }

    @Test
    void whenEveryEntryIsTrustedTheLeftmostIsUsed() {
        assertEquals(
                "10.0.0.7|http|null",
                seen(TRUST_10, "10.0.0.1", "X-Forwarded-For", "10.0.0.7, 10.0.0.2"));
    }

    @Test
    void malformedForwardedValuesAreIgnored() {
        for (String bad :
                new String[] {
                    "not-an-ip",
                    "1.2.3.4, evil.example.com",
                    "",
                    "1.2.3",
                    "1.2.3.4:80x",
                    "999.1.1.1",
                    "1..2.3",
                    "1.2.3.",
                    "1.2.3.4.5",
                    "01.2.3.4x",
                    "localhost",
                    "::g"
                }) {
            assertEquals(
                    "10.0.0.1|http|null", seen(TRUST_10, "10.0.0.1", "X-Forwarded-For", bad), bad);
        }
        assertEquals(
                "10.0.0.1|http|null", seen(TRUST_10, "10.0.0.1", "X-Forwarded-Proto", "gopher"));
        assertEquals("10.0.0.1|http|null", seen(TRUST_10, "10.0.0.1", "X-Forwarded-Host", "a b/c"));
    }

    @Test
    void acceptsSingleAddressesAndIpv6() {
        JoltConfig cfg = JoltConfig.defaults().trustProxy("192.168.1.5", "fd00::/8", "::1");
        assertEquals("1.1.1.1|http|null", seen(cfg, "192.168.1.5", "X-Forwarded-For", "1.1.1.1"));
        assertEquals("1.1.1.1|http|null", seen(cfg, "fd12::9", "X-Forwarded-For", "1.1.1.1"));
        assertEquals("1.1.1.1|http|null", seen(cfg, "::1", "X-Forwarded-For", "1.1.1.1"));
        assertEquals(
                "192.168.1.6|http|null", seen(cfg, "192.168.1.6", "X-Forwarded-For", "1.1.1.1"));
        assertEquals("2001:db8::1|http|null", seen(cfg, "::1", "X-Forwarded-For", "2001:db8::1"));
    }

    @Test
    void invalidConfigurationIsRejected() {
        for (String bad :
                new String[] {
                    "nope",
                    "10.0.0.0/33",
                    "10.0.0.0/x",
                    "::1/129",
                    "",
                    "example.com",
                    "999.1.1.1",
                    "1..2.3",
                    "1.2.3.",
                    "10.0.0.0/-1",
                    "localhost",
                    "1.2.3.4/"
                }) {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> JoltConfig.defaults().trustProxy(bad),
                    bad);
        }
    }

    @Test
    void trustProxyReplacesAndConfigStaysImmutable() {
        JoltConfig base = JoltConfig.defaults();
        JoltConfig trusted = base.trustProxy("10.0.0.0/8");
        assertTrue(base.trustedProxies().isEmpty());
        assertEquals(1, trusted.trustedProxies().size());
        assertTrue(trusted.trustProxy().trustedProxies().isEmpty());
    }

    @Test
    void rateLimitKeysOnTheForwardedAddressWhenTrusted() {
        App app = Jolt.create(TRUST_10);
        app.use(RateLimit.perIp(1, Duration.ofMinutes(1)));
        app.get("/", (req, res) -> res.text("ok"));
        JoltTest.of(app)
                .get("/")
                .remoteAddress("10.0.0.1")
                .header("X-Forwarded-For", "1.1.1.1")
                .expect(200);
        JoltTest.of(app)
                .get("/")
                .remoteAddress("10.0.0.1")
                .header("X-Forwarded-For", "2.2.2.2")
                .expect(200);
        JoltTest.of(app)
                .get("/")
                .remoteAddress("10.0.0.1")
                .header("X-Forwarded-For", "1.1.1.1")
                .expect(429);
    }

    @Test
    void rateLimitIgnoresForwardedAddressFromUntrustedPeers() {
        App app = Jolt.create(TRUST_10);
        app.use(RateLimit.perIp(1, Duration.ofMinutes(1)));
        app.get("/", (req, res) -> res.text("ok"));
        JoltTest.of(app)
                .get("/")
                .remoteAddress("8.8.8.8")
                .header("X-Forwarded-For", "1.1.1.1")
                .expect(200);
        JoltTest.of(app)
                .get("/")
                .remoteAddress("8.8.8.8")
                .header("X-Forwarded-For", "2.2.2.2")
                .expect(429);
    }

    @Test
    void realServerHonoursForwardedHeadersFromATrustedLoopbackPeer() throws Exception {
        try (Server server = app(JoltConfig.defaults().trustProxy("127.0.0.1")).listen(0)) {
            var client = HttpClient.newHttpClient();
            var request =
                    HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + "/"))
                            .header("X-Forwarded-For", "5.6.7.8")
                            .header("X-Forwarded-Proto", "https")
                            .build();
            var body = client.send(request, HttpResponse.BodyHandlers.ofString()).body();
            assertTrue(body.startsWith("5.6.7.8|https|"), body);
        }
        try (Server server = app(JoltConfig.defaults()).listen(0)) {
            var client = HttpClient.newHttpClient();
            var request =
                    HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + "/"))
                            .header("X-Forwarded-For", "5.6.7.8")
                            .build();
            var body = client.send(request, HttpResponse.BodyHandlers.ofString()).body();
            assertTrue(body.startsWith("127.0.0.1|http|"), body);
        }
    }

    @Test
    void requestValuesAreConstructorOnly() {
        var mapper = JoltConfig.defaults().jsonMapper();
        Request r =
                new Request(
                        "GET",
                        "/",
                        "",
                        java.util.Map.of("Host", "h"),
                        null,
                        mapper,
                        "1.1.1.1",
                        "https",
                        "x.test");
        assertEquals("https", r.scheme());
        assertEquals("x.test", r.host());
        Request plain = new Request("GET", "/", "", java.util.Map.of("Host", "h"), null, mapper);
        assertEquals("http", plain.scheme());
        assertEquals("h", plain.host());
        for (var m : Request.class.getMethods()) {
            if (m.getName().equals("scheme") || m.getName().equals("host")) {
                assertEquals(0, m.getParameterCount());
            }
        }
    }
}
