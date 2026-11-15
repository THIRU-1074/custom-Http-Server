package in.jolt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import in.jolt.test.JoltTest;
import java.util.ArrayList;
import java.util.List;
import java.util.ResourceBundle;
import org.junit.jupiter.api.Test;

class RequestLogTest {

    private static final class Capture implements System.Logger {
        final List<String> lines = new ArrayList<>();

        @Override
        public String getName() {
            return "capture";
        }

        @Override
        public boolean isLoggable(Level level) {
            return true;
        }

        @Override
        public void log(Level level, ResourceBundle bundle, String msg, Throwable thrown) {
            lines.add(msg);
        }

        @Override
        public void log(Level level, ResourceBundle bundle, String format, Object... params) {
            lines.add(format);
        }
    }

    private static App app(Capture log) {
        App app = Jolt.create();
        app.use(Logger.requests(log));
        app.get("/hi", (req, res) -> res.text("ok"));
        app.get(
                "/boom",
                (req, res) -> {
                    throw new IllegalStateException("secret");
                });
        app.get(
                "/nf",
                (req, res) -> {
                    throw new HttpException(404, "nope");
                });
        return app;
    }

    @Test
    void logsMethodPathStatusAndDuration() {
        Capture log = new Capture();
        JoltTest.of(app(log)).get("/hi").expect(200);
        assertEquals(1, log.lines.size());
        assertTrue(log.lines.get(0).matches("GET /hi 200 \\d+ms"), log.lines.get(0));
    }

    @Test
    void neverLogsQueryHeadersOrCookies() {
        Capture log = new Capture();
        JoltTest.of(app(log))
                .get("/hi?token=abc123")
                .header("Authorization", "Bearer s3cr3t")
                .header("Cookie", "sid=cookieval")
                .expect(200);
        String line = log.lines.get(0);
        assertFalse(line.contains("abc123") || line.contains("token"));
        assertFalse(line.contains("s3cr3t") || line.contains("cookieval"));
    }

    @Test
    void logsErrorStatuses() {
        Capture log = new Capture();
        JoltTest.of(app(log)).get("/boom").expect(500);
        JoltTest.of(app(log)).get("/nf").expect(404);
        assertTrue(log.lines.get(0).startsWith("GET /boom 500 "), log.lines.get(0));
        assertTrue(log.lines.get(1).startsWith("GET /nf 404 "), log.lines.get(1));
    }

    @Test
    void exceptionsStillPropagate() {
        Request req =
                new Request(
                        "GET",
                        "/",
                        "",
                        java.util.Map.of(),
                        null,
                        JoltConfig.defaults().jsonMapper());
        Middleware mw = Logger.requests(new Capture());
        assertThrows(
                IllegalStateException.class,
                () ->
                        mw.handle(
                                req,
                                new Response(null),
                                () -> {
                                    throw new IllegalStateException();
                                }));
    }

    @Test
    void controlCharactersInPathCannotForgeLogLines() {
        Capture log = new Capture();
        JoltTest.of(app(log)).get("/a%0AGET%20/forged").send();
        assertFalse(log.lines.get(0).contains("\n"));
    }

    @Test
    void unicodeSeparatorsAndOddMethodsAreSanitized() {
        Capture log = new Capture();
        JoltTest.of(app(log)).get("/a%E2%80%A8b%E2%80%A9c%C2%85d").send();
        JoltTest.of(app(log)).call("G\nE" + (char) 0x2028 + "T", "/hi").send();
        JoltTest.of(app(log)).call("X" + (char) 0x200B + "Y" + (char) 0xFFFF, "/hi").send();
        assertTrue(log.lines.get(0).startsWith("GET /a?b?c?d "), log.lines.get(0));
        assertTrue(log.lines.get(1).startsWith("G?E?T /hi "), log.lines.get(1));
        assertTrue(log.lines.get(2).startsWith("X?Y? /hi "), log.lines.get(2));
    }

    @Test
    void publicFactoryUsesSystemLogger() {
        App app = Jolt.create();
        app.use(Logger.requests());
        app.get("/hi", (req, res) -> res.text("ok"));
        JoltTest.of(app).get("/hi").expect(200);
    }
}
