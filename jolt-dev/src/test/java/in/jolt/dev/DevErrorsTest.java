package in.jolt.dev;

import in.jolt.App;
import in.jolt.HttpException;
import in.jolt.Jolt;
import in.jolt.Middleware;
import in.jolt.Reply;
import in.jolt.test.JoltTest;
import org.junit.jupiter.api.Test;

class DevErrorsTest {

    private static App boom(Middleware mw) {
        var app = Jolt.create();
        app.use(mw);
        app.get(
                "/boom",
                (req, res) -> {
                    throw new IllegalStateException("bad <state>");
                });
        app.get(
                "/nope",
                (req, res) -> {
                    throw new HttpException(404, "missing");
                });
        app.get("/ok", (req, res) -> res.text("fine"));
        return app;
    }

    private static void assertNoLeak(String text) {
        if (text.contains("DevErrorsTest") || text.contains("bad")) {
            throw new AssertionError("exception detail leaked: " + text);
        }
    }

    @Test
    void rendersExceptionAndStackWhenEnabled() {
        JoltTest.of(boom(DevErrors.page(() -> true)))
                .get("/boom")
                .expect(500)
                .header("Content-Type", "text/html; charset=utf-8")
                .bodyContains("IllegalStateException")
                .bodyContains("bad &lt;state&gt;")
                .bodyContains("DevErrorsTest");
    }

    @Test
    void doesNothingWhenNotInDevMode() {
        assertNoLeak(
                JoltTest.of(boom(DevErrors.page(() -> false))).get("/boom").expect(500).text());
    }

    @Test
    void notInstalledMeansNoStackLeak() {
        var app = Jolt.create();
        app.get(
                "/boom",
                (req, res) -> {
                    throw new IllegalStateException("bad");
                });
        assertNoLeak(JoltTest.of(app).get("/boom").expect(500).text());
    }

    @Test
    void httpExceptionsKeepProductionHandling() {
        var text = JoltTest.of(boom(DevErrors.page(() -> true))).get("/nope").expect(404).text();
        if (text.contains("<pre>")) throw new AssertionError("HttpException rendered as page");
    }

    @Test
    void passesThroughWhenNothingFails() {
        JoltTest.of(boom(DevErrors.page(() -> true))).get("/ok").expect(200).body("fine");
    }

    @Test
    void appAwareFormLeavesMappedErrorsToTheApp() {
        var app = Jolt.create();
        app.onError(IllegalStateException.class, e -> Reply.status(409, "mapped"));
        app.use(DevErrors.page(app, () -> true));
        app.get(
                "/boom",
                (req, res) -> {
                    throw new IllegalStateException("x");
                });
        app.get(
                "/other",
                (req, res) -> {
                    throw new UnsupportedOperationException("y");
                });
        var test = JoltTest.of(app);
        test.get("/boom").expect(409).bodyContains("mapped");
        test.get("/other").expect(500).bodyContains("UnsupportedOperationException");
    }

    @Test
    void enabledByDefaultOnlyWithEnvOrProperty() {
        boolean expected =
                System.getenv("JOLT_DEV") != null || "true".equals(System.getProperty("jolt.dev"));
        if (DevErrors.devMode() != expected) throw new AssertionError("devMode mismatch");
        System.setProperty("jolt.dev", "true");
        try {
            if (!DevErrors.devMode()) throw new AssertionError("property should enable");
        } finally {
            System.clearProperty("jolt.dev");
        }
    }
}
