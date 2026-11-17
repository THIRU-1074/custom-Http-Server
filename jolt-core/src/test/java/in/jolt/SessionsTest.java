package in.jolt;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import in.jolt.test.JoltTest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class SessionsTest {

    private static final String KEY = "0123456789abcdef0123456789abcdef";
    private static final String OLD = "fedcba9876543210fedcba9876543210";

    private static final class MutableClock extends Clock {
        Instant now = Instant.parse("2030-01-01T00:00:00Z");

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    private static App app(Sessions sessions) {
        App app = Jolt.create();
        app.use(sessions);
        routes(app);
        return app;
    }

    private static void routes(App app) {
        app.get(
                "/set/:v",
                (req, res) -> {
                    Sessions.of(req).put("user", req.param("v"));
                    res.text("set");
                });
        app.get(
                "/get",
                (req, res) -> {
                    Session s = Sessions.of(req);
                    res.text(s.get("user") + " " + s.isNew());
                });
        app.get("/noop", (req, res) -> res.text("noop"));
        app.get(
                "/clear",
                (req, res) -> {
                    Sessions.of(req).clear();
                    res.text("cleared");
                });
        app.get(
                "/big",
                (req, res) -> {
                    Sessions.of(req).put("blob", "x".repeat(4000));
                    res.text("big");
                });
    }

    @Test
    void roundTripThroughTheCookieJar() {
        JoltTest t = JoltTest.of(app(Sessions.signed(KEY))).session();
        t.get("/get").expect(200).body("null true");
        t.get("/set/ada").expect(200);
        t.get("/get").expect(200).body("ada false");
    }

    @Test
    void cookieAttributesAndFormat() {
        var r = JoltTest.of(app(Sessions.signed(KEY))).get("/set/ada").expect(200);
        String c = r.header("Set-Cookie");
        assertTrue(c.matches("session=[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+; .*"), c);
        assertTrue(c.contains("HttpOnly"), c);
        assertTrue(c.contains("SameSite=Lax"), c);
        assertTrue(c.contains("Path=/"), c);
        assertFalse(c.contains("Secure"), c);
    }

    @Test
    void secureWhenConfigured() {
        App app = Jolt.create(JoltConfig.defaults().secureCookies(true));
        app.use(Sessions.signed(KEY));
        routes(app);
        assertTrue(JoltTest.of(app).get("/set/a").send().header("Set-Cookie").contains("Secure"));
    }

    @Test
    void unchangedSessionSetsNoCookie() {
        JoltTest t = JoltTest.of(app(Sessions.signed(KEY))).session();
        assertNull(t.get("/noop").expect(200).header("Set-Cookie"));
        t.get("/set/ada").expect(200);
        assertNull(t.get("/get").expect(200).header("Set-Cookie"));
    }

    @Test
    void tamperedCookieIsRejected() {
        App app = app(Sessions.signed(KEY));
        String cookie = JoltTest.of(app).get("/set/ada").send().header("Set-Cookie");
        String value = cookie.substring("session=".length(), cookie.indexOf(';'));
        String[] parts = value.split("\\.");
        String forgedPayload =
                java.util.Base64.getUrlEncoder()
                        .withoutPadding()
                        .encodeToString("{\"e\":9999999999,\"d\":{\"user\":\"root\"}}".getBytes());
        for (String bad :
                new String[] {
                    forgedPayload + "." + parts[1],
                    parts[0] + "." + flipFirst(parts[1]),
                    parts[0],
                    "." + parts[1],
                    "garbage"
                }) {
            JoltTest.of(app)
                    .get("/get")
                    .header("Cookie", "session=" + bad)
                    .expect(200)
                    .body("null true");
        }
    }

    private static String flipFirst(String s) {
        return (s.charAt(0) == 'A' ? "B" : "A") + s.substring(1);
    }

    @Test
    void nonCanonicalSignatureEncodingIsRejected() {
        App app = app(Sessions.signed(KEY));
        String cookie = JoltTest.of(app).get("/set/ada").send().header("Set-Cookie");
        String value = cookie.substring("session=".length(), cookie.indexOf(';'));
        String[] parts = value.split("\\.");
        String alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_";
        char last = parts[1].charAt(parts[1].length() - 1);
        String variant =
                parts[1].substring(0, parts[1].length() - 1)
                        + alphabet.charAt(alphabet.indexOf(last) | 1);
        assertNotEquals(parts[1], variant);
        assertArrayEquals(
                java.util.Base64.getUrlDecoder().decode(parts[1]),
                java.util.Base64.getUrlDecoder().decode(variant));
        JoltTest.of(app)
                .get("/get")
                .header("Cookie", "session=" + parts[0] + "." + variant)
                .expect(200)
                .body("null true");
    }

    @Test
    void cookieSignedWithAnotherKeyIsRejected() {
        String cookie =
                JoltTest.of(app(Sessions.signed(OLD))).get("/set/ada").send().header("Set-Cookie");
        JoltTest.of(app(Sessions.signed(KEY)))
                .get("/get")
                .header("Cookie", cookie.substring(0, cookie.indexOf(';')))
                .expect(200)
                .body("null true");
    }

    @Test
    void expiredSessionIsRejected() {
        MutableClock clock = new MutableClock();
        JoltTest t = JoltTest.of(app(Sessions.signed(KEY).clock(clock))).session();
        t.get("/set/ada").expect(200);
        clock.now = clock.now.plus(Duration.ofMinutes(59));
        t.get("/get").expect(200).body("ada false");
        clock.now = clock.now.plus(Duration.ofMinutes(61));
        t.get("/get").expect(200).body("null true");
    }

    @Test
    void customMaxAge() {
        MutableClock clock = new MutableClock();
        JoltTest t =
                JoltTest.of(app(Sessions.signed(KEY).maxAge(Duration.ofMinutes(5)).clock(clock)))
                        .session();
        t.get("/set/ada").expect(200);
        clock.now = clock.now.plus(Duration.ofMinutes(6));
        t.get("/get").expect(200).body("null true");
    }

    @Test
    void expiryIsRenewedOnceHalfTheLifetimeIsUsed() {
        MutableClock clock = new MutableClock();
        JoltTest t = JoltTest.of(app(Sessions.signed(KEY).clock(clock))).session();
        t.get("/set/ada").expect(200);
        clock.now = clock.now.plus(Duration.ofMinutes(20));
        assertNull(t.get("/get").expect(200).header("Set-Cookie"));
        clock.now = clock.now.plus(Duration.ofMinutes(20));
        assertTrue(t.get("/get").expect(200).header("Set-Cookie") != null);
        clock.now = clock.now.plus(Duration.ofMinutes(50));
        t.get("/get").expect(200).body("ada false");
    }

    @Test
    void keyRotationAcceptsPreviousKeysAndResignsWithTheFirst() {
        String old =
                JoltTest.of(app(Sessions.signed(OLD))).get("/set/ada").send().header("Set-Cookie");
        String oldCookie = old.substring(0, old.indexOf(';'));
        App rotated = app(Sessions.signed(KEY).previous(OLD));
        var r = JoltTest.of(rotated).get("/get").header("Cookie", oldCookie).expect(200);
        r.body("ada false");
        String fresh = r.header("Set-Cookie");
        JoltTest.of(app(Sessions.signed(KEY)))
                .get("/get")
                .header("Cookie", fresh.substring(0, fresh.indexOf(';')))
                .expect(200)
                .body("ada false");
    }

    @Test
    void sizeCapThrowsOnWrite() {
        App app = app(Sessions.signed(KEY));
        JoltTest.of(app).get("/big").expect(500);
        var s = new Session();
        IllegalStateException e =
                assertThrows(IllegalStateException.class, () -> s.put("blob", "x".repeat(4000)));
        assertTrue(e.getMessage().contains("3072"), e.getMessage());
        assertNull(s.get("blob"));
    }

    @Test
    void clearRemovesTheCookie() {
        JoltTest t = JoltTest.of(app(Sessions.signed(KEY))).session();
        t.get("/set/ada").expect(200);
        assertTrue(t.get("/clear").expect(200).header("Set-Cookie").contains("Max-Age=0"));
        t.get("/get").expect(200).body("null true");
    }

    @Test
    void storesOnlyStringsNumbersAndBooleans() {
        var s = new Session();
        s.put("s", "a").put("n", 1).put("d", 1.5).put("b", true);
        assertThrows(IllegalArgumentException.class, () -> s.put("o", new Object()));
        assertThrows(IllegalArgumentException.class, () -> s.put("l", java.util.List.of()));
        assertThrows(IllegalArgumentException.class, () -> s.put("x", null));
        assertThrows(IllegalArgumentException.class, () -> s.put("nan", Double.NaN));
        assertEquals("a", s.get("s"));
    }

    @Test
    void numbersAndBooleansRoundTrip() {
        App app = Jolt.create();
        app.use(Sessions.signed(KEY));
        app.get(
                "/w",
                (req, res) -> {
                    Sessions.of(req).put("n", 7).put("b", true);
                    res.text("w");
                });
        app.get(
                "/r",
                (req, res) -> {
                    Session s = Sessions.of(req);
                    res.text(s.get("n") + " " + s.get("b"));
                });
        JoltTest t = JoltTest.of(app).session();
        t.get("/w").expect(200);
        t.get("/r").expect(200).body("7 true");
    }

    @Test
    void sessionOfWithoutMiddlewareFails() {
        App app = Jolt.create();
        app.get("/x", (req, res) -> res.text(String.valueOf(Sessions.of(req))));
        JoltTest.of(app).get("/x").expect(500);
    }

    @Test
    void secretsMustBeAtLeast32Bytes() {
        assertThrows(IllegalArgumentException.class, () -> Sessions.signed("short"));
        assertThrows(IllegalArgumentException.class, () -> Sessions.signed(new byte[31]));
        assertThrows(IllegalArgumentException.class, () -> Sessions.signed(KEY).previous("short"));
        Sessions.signed(new byte[32]);
    }

    @Test
    void cookieIsWrittenEvenWhenTheHandlerThrows() {
        App app = Jolt.create();
        app.use(Sessions.signed(KEY));
        app.get(
                "/fail",
                (req, res) -> {
                    Sessions.of(req).put("n", 1);
                    throw new HttpException(401, "no");
                });
        routes(app);
        JoltTest t = JoltTest.of(app).session();
        var r = t.get("/fail").expect(401);
        assertTrue(r.header("Set-Cookie").startsWith("session="));
        t.get("/get").expect(200).body("null false");
    }

    @Test
    void clearBeforeAThrowClearsTheCookie() {
        App app = Jolt.create();
        app.use(Sessions.signed(KEY));
        app.get(
                "/logout",
                (req, res) -> {
                    Sessions.of(req).clear();
                    throw new HttpException(401, "bye");
                });
        routes(app);
        JoltTest t = JoltTest.of(app).session();
        t.get("/set/ada").expect(200);
        assertTrue(t.get("/logout").expect(401).header("Set-Cookie").contains("Max-Age=0"));
        t.get("/get").expect(200).body("null true");
    }
}
