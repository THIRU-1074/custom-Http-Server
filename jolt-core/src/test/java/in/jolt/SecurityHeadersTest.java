package in.jolt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import in.jolt.test.JoltTest;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class SecurityHeadersTest {

    private static App app(SecurityHeaders mw) {
        App app = Jolt.create();
        app.use(mw);
        app.get("/", (req, res) -> res.text("ok"));
        app.get(
                "/boom",
                (req, res) -> {
                    throw new HttpException(403, "no");
                });
        return app;
    }

    @Test
    void defaultsSetTheFiveHeaders() {
        JoltTest.of(app(SecurityHeaders.defaults()))
                .get("/")
                .expect(200)
                .header("X-Content-Type-Options", "nosniff")
                .header("X-Frame-Options", "DENY")
                .header("Referrer-Policy", "no-referrer")
                .header("Cross-Origin-Opener-Policy", "same-origin")
                .header("Cross-Origin-Resource-Policy", "same-origin");
    }

    @Test
    void defaultsOmitHstsAndCsp() {
        var r = JoltTest.of(app(SecurityHeaders.defaults())).get("/").expect(200);
        assertNull(r.header("Strict-Transport-Security"));
        assertNull(r.header("Content-Security-Policy"));
    }

    @Test
    void hstsIsNotSentOverPlainHttp() {
        var r =
                JoltTest.of(app(SecurityHeaders.defaults().hsts(Duration.ofDays(365))))
                        .get("/")
                        .expect(200);
        assertNull(r.header("Strict-Transport-Security"));
    }

    @Test
    void hstsIsSentWhenThePredicateSaysHttps() {
        var mw =
                SecurityHeaders.defaults()
                        .hsts(Duration.ofDays(365))
                        .https(req -> "https".equals(req.header("X-Scheme")));
        var t = JoltTest.of(app(mw));
        t.get("/")
                .header("X-Scheme", "https")
                .expect(200)
                .header("Strict-Transport-Security", "max-age=31536000; includeSubDomains");
        assertNull(t.get("/").expect(200).header("Strict-Transport-Security"));
    }

    @Test
    void cspIsSetWhenGiven() {
        JoltTest.of(app(SecurityHeaders.defaults().csp("default-src 'self'")))
                .get("/")
                .expect(200)
                .header("Content-Security-Policy", "default-src 'self'");
    }

    @Test
    void headersSurviveErrorReplies() {
        JoltTest.of(app(SecurityHeaders.defaults()))
                .get("/boom")
                .expect(403)
                .header("X-Frame-Options", "DENY");
    }

    @Test
    void headersSurviveUnknownRoutes() {
        JoltTest.of(app(SecurityHeaders.defaults()))
                .get("/nope")
                .expect(404)
                .header("X-Content-Type-Options", "nosniff");
    }

    @Test
    void controlCharactersInCspAreRejectedWhenConfiguring() {
        var d = SecurityHeaders.defaults();
        assertThrows(IllegalArgumentException.class, () -> d.csp("a\r\nSet-Cookie: x=1"));
    }

    @Test
    void hstsNeedsAPositiveDuration() {
        var d = SecurityHeaders.defaults();
        assertThrows(IllegalArgumentException.class, () -> d.hsts(Duration.ZERO));
        assertEquals(
                "max-age=60; includeSubDomains",
                SecurityHeaders.defaults().hsts(Duration.ofMinutes(1)).hstsValue());
    }
}
