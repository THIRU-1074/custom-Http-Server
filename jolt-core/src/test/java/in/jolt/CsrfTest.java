package in.jolt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import in.jolt.test.JoltTest;
import org.junit.jupiter.api.Test;

class CsrfTest {

    private static App app(Csrf csrf) {
        App app = Jolt.create();
        app.use(csrf);
        app.get("/form", (req, res) -> res.text(String.valueOf(Csrf.token(req))));
        Handler ok = (req, res) -> res.text("ok");
        app.post("/act", ok);
        app.put("/act", ok);
        app.patch("/act", ok);
        app.delete("/act", ok);
        return app;
    }

    private static JoltTest.Call post(App app) {
        return JoltTest.of(app)
                .post("/act")
                .header("Host", "example.com")
                .header("Cookie", "sid=1");
    }

    @Test
    void safeMethodsPassEvenCrossSite() {
        JoltTest.of(app(Csrf.protect()))
                .get("/form")
                .header("Cookie", "sid=1")
                .header("Sec-Fetch-Site", "cross-site")
                .expect(200);
    }

    @Test
    void fetchMetadataDecidesWhenPresent() {
        App app = app(Csrf.protect());
        for (String ok : new String[] {"same-origin", "same-site", "none"}) {
            post(app).header("Sec-Fetch-Site", ok).expect(200);
        }
        post(app).header("Sec-Fetch-Site", "cross-site").expect(403);
        post(app).header("Sec-Fetch-Site", "bogus").expect(403);
    }

    @Test
    void fetchMetadataWinsOverOrigin() {
        post(app(Csrf.protect()))
                .header("Sec-Fetch-Site", "cross-site")
                .header("Origin", "http://example.com")
                .expect(403);
    }

    @Test
    void originMustMatchHostWhenNoFetchMetadata() {
        App app = app(Csrf.protect());
        post(app).header("Origin", "http://example.com").expect(200);
        post(app).header("Origin", "https://EXAMPLE.com").expect(200);
        post(app).header("Origin", "http://evil.com").expect(403);
        post(app).header("Origin", "http://example.com.evil.com").expect(403);
        post(app).header("Origin", "http://example.com:8080").expect(403);
        post(app).header("Origin", "null").expect(403);
        post(app).header("Origin", "not a url").expect(403);
    }

    @Test
    void refererIsUsedWhenThereIsNoOrigin() {
        App app = app(Csrf.protect());
        post(app).header("Referer", "https://example.com/page?x=1").expect(200);
        post(app).header("Referer", "https://evil.com/page").expect(403);
    }

    @Test
    void cookieRequestWithNoProvenanceIsRejected() {
        post(app(Csrf.protect())).expect(403);
    }

    @Test
    void rejectionIsA403WithOneLine() {
        String body = post(app(Csrf.protect())).expect(403).text();
        assertFalse(body.isBlank());
        assertFalse(body.strip().contains("\n"), body);
    }

    @Test
    void allUnsafeMethodsAreChecked() {
        App app = app(Csrf.protect());
        for (String m : new String[] {"POST", "PUT", "PATCH", "DELETE"}) {
            JoltTest.of(app)
                    .call(m, "/act")
                    .header("Cookie", "sid=1")
                    .header("Sec-Fetch-Site", "cross-site")
                    .expect(403);
            JoltTest.of(app)
                    .call(m, "/act")
                    .header("Cookie", "sid=1")
                    .header("Sec-Fetch-Site", "same-origin")
                    .expect(200);
        }
    }

    @Test
    void cookielessRequestsSkipTheCheckUnlessStrict() {
        JoltTest.of(app(Csrf.protect())).post("/act").expect(200);
        JoltTest.of(app(Csrf.protect())).post("/act").header("Cookie", " ").expect(200);
        App strict = app(Csrf.protect().strict());
        JoltTest.of(strict).post("/act").expect(403);
        JoltTest.of(strict).post("/act").header("Sec-Fetch-Site", "same-origin").expect(200);
        JoltTest.of(strict)
                .post("/act")
                .header("Host", "example.com")
                .header("Origin", "http://example.com")
                .expect(200);
    }

    @Test
    void tokenModeIssuesAReadableCookieOnSafeRequests() {
        JoltTest t = JoltTest.of(app(Csrf.protect().token())).session();
        var r = t.get("/form").expect(200);
        String c = r.header("Set-Cookie");
        assertTrue(c.startsWith("csrf="), c);
        assertTrue(c.contains("SameSite=Lax"), c);
        assertFalse(c.contains("HttpOnly"), c);
        String token = c.substring(5, c.indexOf(';'));
        assertTrue(token.length() >= 43, token);
        r.body(token);
        assertNull(t.get("/form").expect(200).header("Set-Cookie"));
        t.get("/form").expect(200).body(token);
    }

    @Test
    void tokenCookieIsSecureWhenConfigured() {
        App app = Jolt.create(JoltConfig.defaults().secureCookies(true));
        app.use(Csrf.protect().token());
        app.get("/form", (req, res) -> res.text("x"));
        assertTrue(JoltTest.of(app).get("/form").send().header("Set-Cookie").contains("Secure"));
    }

    @Test
    void tokenModeRequiresTheTokenOnUnsafeRequests() {
        App app = app(Csrf.protect().token());
        JoltTest t = JoltTest.of(app).session();
        String token = t.get("/form").send().text();
        t.post("/act").header("Sec-Fetch-Site", "same-origin").expect(403);
        t.post("/act")
                .header("Sec-Fetch-Site", "same-origin")
                .header("X-CSRF-Token", "wrong")
                .expect(403);
        t.post("/act")
                .header("Sec-Fetch-Site", "same-origin")
                .header("X-CSRF-Token", token)
                .expect(200);
    }

    @Test
    void tokenModeAcceptsTheFormField() {
        App app = app(Csrf.protect().token());
        JoltTest t = JoltTest.of(app).session();
        String token = t.get("/form").send().text();
        t.post("/act")
                .header("Sec-Fetch-Site", "same-origin")
                .header("Content-Type", "application/x-www-form-urlencoded")
                .body(("a=1&_csrf=" + token).getBytes())
                .expect(200);
        t.post("/act")
                .header("Sec-Fetch-Site", "same-origin")
                .header("Content-Type", "application/x-www-form-urlencoded")
                .body("a=1&_csrf=nope".getBytes())
                .expect(403);
    }

    @Test
    void tokenModeStillRequiresSameSiteIntent() {
        App app = app(Csrf.protect().token());
        JoltTest t = JoltTest.of(app).session();
        String token = t.get("/form").send().text();
        t.post("/act")
                .header("Sec-Fetch-Site", "cross-site")
                .header("X-CSRF-Token", token)
                .expect(403);
    }

    @Test
    void tokenModeRejectsWhenTheCookieIsMissing() {
        JoltTest.of(app(Csrf.protect().token()))
                .post("/act")
                .header("Cookie", "sid=1")
                .header("Sec-Fetch-Site", "same-origin")
                .header("X-CSRF-Token", "abc")
                .expect(403);
    }

    @Test
    void tokenHelperIsNullWithoutTokenMode() {
        JoltTest.of(app(Csrf.protect())).get("/form").expect(200).body("null");
        assertNotNull(Csrf.protect());
        assertEquals(null, Csrf.token(new Request("GET", "/", "", java.util.Map.of(), null, null)));
    }
}
