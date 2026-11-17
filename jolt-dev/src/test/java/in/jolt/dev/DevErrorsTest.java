package in.jolt.dev;

import in.jolt.HttpException;
import in.jolt.Jolt;
import in.jolt.test.JoltTest;
import org.junit.jupiter.api.Test;

class DevErrorsTest {

    @Test
    void rendersExceptionAndStackWhenInstalled() {
        var app = Jolt.create();
        app.use(DevErrors.page());
        app.get(
                "/boom",
                (req, res) -> {
                    throw new IllegalStateException("bad <state>");
                });
        JoltTest.of(app)
                .get("/boom")
                .expect(500)
                .header("Content-Type", "text/html; charset=utf-8")
                .bodyContains("IllegalStateException")
                .bodyContains("bad &lt;state&gt;")
                .bodyContains("DevErrorsTest");
    }

    @Test
    void keepsStatusOfHttpException() {
        var app = Jolt.create();
        app.use(DevErrors.page());
        app.get(
                "/nope",
                (req, res) -> {
                    throw new HttpException(404, "missing");
                });
        JoltTest.of(app).get("/nope").expect(404).bodyContains("missing");
    }

    @Test
    void passesThroughWhenNothingFails() {
        var app = Jolt.create();
        app.use(DevErrors.page());
        app.get("/ok", (req, res) -> res.text("fine"));
        JoltTest.of(app).get("/ok").expect(200).body("fine");
    }

    @Test
    void notInstalledMeansNoStackLeak() {
        var app = Jolt.create();
        app.get(
                "/boom",
                (req, res) -> {
                    throw new IllegalStateException("secret detail");
                });
        var result = JoltTest.of(app).get("/boom").expect(500);
        if (result.text().contains("DevErrorsTest") || result.text().contains("secret detail")) {
            throw new AssertionError("default error response must not expose the exception");
        }
    }
}
