package in.jolt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import in.jolt.test.JoltTest;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.ResourceBundle;
import org.junit.jupiter.api.Test;

class RequestIdTest {

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

    private static App app() {
        App app = Jolt.create();
        app.use(RequestId.middleware());
        app.get("/id", (req, res) -> res.text(req.id()));
        return app;
    }

    @Test
    void generatesAnIdAndEchoesIt() {
        var r = JoltTest.of(app()).get("/id").expect(200);
        String id = r.header("X-Request-Id");
        assertTrue(id.matches("[0-9a-f]{16}"), id);
        r.body(id);
    }

    @Test
    void generatedIdsDiffer() {
        JoltTest t = JoltTest.of(app());
        assertNotEquals(
                t.get("/id").send().header("X-Request-Id"),
                t.get("/id").send().header("X-Request-Id"));
    }

    @Test
    void propagatesAValidIncomingId() {
        JoltTest.of(app())
                .get("/id")
                .header("X-Request-Id", "abc-123_DEF.9")
                .expect(200)
                .header("X-Request-Id", "abc-123_DEF.9")
                .body("abc-123_DEF.9");
    }

    @Test
    void replacesUnsafeIncomingIds() {
        String[] bad = {"a\r\nSet-Cookie: x=1", "a b", "x".repeat(65), "café", "", "a/b", "id\n"};
        for (String value : bad) {
            String id =
                    JoltTest.of(app())
                            .get("/id")
                            .header("X-Request-Id", value)
                            .expect(200)
                            .header("X-Request-Id");
            assertTrue(id.matches("[0-9a-f]{16}"), value + " -> " + id);
        }
    }

    @Test
    void acceptsMaximumLength() {
        String id = "x".repeat(64);
        JoltTest.of(app()).get("/id").header("X-Request-Id", id).expect(200).body(id);
    }

    @Test
    void idIsNullWithoutTheMiddleware() {
        App app = Jolt.create();
        app.get("/id", (req, res) -> res.text(String.valueOf(req.id())));
        JoltTest.of(app).get("/id").expect(200).body("null");
        assertNull(new Request("GET", "/", "", Map.of(), null, null).id());
    }

    @Test
    void errorResponsesCarryTheHeader() {
        App app = Jolt.create();
        app.use(RequestId.middleware());
        app.get("/x", (req, res) -> res.text("x"));
        assertTrue(
                JoltTest.of(app)
                        .get("/missing")
                        .expect(404)
                        .header("X-Request-Id")
                        .matches("[0-9a-f]{16}"));
    }

    @Test
    void loggerIncludesTheId() {
        Capture log = new Capture();
        App app = Jolt.create();
        app.use(RequestId.middleware());
        app.use(Logger.requests(log));
        app.get("/hi", (req, res) -> res.text("ok"));
        JoltTest.of(app).get("/hi").header("X-Request-Id", "rid-1").expect(200);
        assertEquals(1, log.lines.size());
        assertTrue(log.lines.get(0).startsWith("GET /hi 200 "), log.lines.get(0));
        assertTrue(log.lines.get(0).endsWith(" id=rid-1"), log.lines.get(0));
    }

    @Test
    void loggerOmitsTheIdWhenAbsent() {
        Capture log = new Capture();
        App app = Jolt.create();
        app.use(Logger.requests(log));
        app.get("/hi", (req, res) -> res.text("ok"));
        JoltTest.of(app).get("/hi").expect(200);
        assertFalse(log.lines.get(0).contains("id="));
    }

    private static App traceApp() {
        App app = Jolt.create();
        app.use(RequestId.middleware());
        app.get("/t", (req, res) -> res.text(String.valueOf(req.traceId())));
        return app;
    }

    @Test
    void readsTheTraceIdFromAValidTraceparent() {
        JoltTest.of(traceApp())
                .get("/t")
                .header("traceparent", "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01")
                .expect(200)
                .body("4bf92f3577b34da6a3ce929d0e0e4736");
    }

    @Test
    void acceptsFutureVersionsWithExtraFields() {
        JoltTest.of(traceApp())
                .get("/t")
                .header(
                        "traceparent",
                        "01-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01-extra")
                .expect(200)
                .body("4bf92f3577b34da6a3ce929d0e0e4736");
    }

    @Test
    void ignoresInvalidTraceparents() {
        String[] bad = {
            "ff-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01",
            "00-00000000000000000000000000000000-00f067aa0ba902b7-01",
            "00-4bf92f3577b34da6a3ce929d0e0e4736-0000000000000000-01",
            "00-4BF92F3577B34DA6A3CE929D0E0E4736-00f067aa0ba902b7-01",
            "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01-x",
            "00-4bf92f3577b34da6a3ce929d0e0e473-00f067aa0ba902b7-01",
            "garbage"
        };
        for (String value : bad) {
            assertEquals(
                    "null",
                    JoltTest.of(traceApp())
                            .get("/t")
                            .header("traceparent", value)
                            .expect(200)
                            .text(),
                    value);
        }
    }

    @Test
    void loggerIncludesTheTraceId() {
        Capture log = new Capture();
        App app = Jolt.create();
        app.use(RequestId.middleware());
        app.use(Logger.requests(log));
        app.get("/hi", (req, res) -> res.text("ok"));
        JoltTest.of(app)
                .get("/hi")
                .header("traceparent", "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01")
                .expect(200);
        assertTrue(
                log.lines.get(0).endsWith(" trace=4bf92f3577b34da6a3ce929d0e0e4736"),
                log.lines.get(0));
    }
}
