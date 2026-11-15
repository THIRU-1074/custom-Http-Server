package in.jolt;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import in.jolt.test.JoltTest;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ErrorMappingHttpExceptionTest {

    static class Bug extends IllegalStateException {
        Bug(String m) {
            super(m);
        }
    }

    static class Gone extends HttpException {
        Gone() {
            super(410, "gone for good");
        }
    }

    private final List<LogRecord> logs = new ArrayList<>();
    private final java.util.logging.Logger logger = java.util.logging.Logger.getLogger("in.jolt");
    private final Handler handler =
            new Handler() {
                @Override
                public void publish(LogRecord r) {
                    logs.add(r);
                }

                @Override
                public void flush() {}

                @Override
                public void close() {}
            };

    @BeforeEach
    void attach() {
        logger.addHandler(handler);
    }

    @AfterEach
    void detach() {
        logger.removeHandler(handler);
    }

    private static App app() {
        App app = Jolt.create();
        app.get(
                "/http",
                (req, res) -> {
                    throw new HttpException(403, "forbidden");
                });
        app.get(
                "/gone",
                (req, res) -> {
                    throw new Gone();
                });
        app.get(
                "/bug",
                (req, res) -> {
                    throw new Bug("secret detail");
                });
        return app;
    }

    @Test
    void broadMappersDoNotCaptureHttpExceptions() {
        for (Class<? extends Throwable> broad :
                List.of(RuntimeException.class, Exception.class, Throwable.class)) {
            App a = app();
            a.onError(broad, e -> Reply.status(500, "mapped"));
            JoltTest.of(a).get("/http").expect(403).body("forbidden");
        }
    }

    @Test
    void mapperForHttpExceptionSubclassStillApplies() {
        App a = app();
        a.onError(RuntimeException.class, e -> Reply.status(500, "mapped"));
        a.onError(Gone.class, e -> Reply.status(404, "mapped gone"));
        JoltTest.of(a).get("/gone").expect(404).bodyContains("mapped gone");
        JoltTest.of(a).get("/http").expect(403).body("forbidden");
    }

    @Test
    void mappedNonHttpExceptionsAreLoggedWithTheException() {
        App a = app();
        a.onError(Bug.class, e -> Reply.status(500, "oops"));
        String body = JoltTest.of(a).get("/bug").expect(500).text();
        assertFalse(body.contains("secret detail"));
        assertTrue(
                logs.stream()
                        .anyMatch(
                                r ->
                                        r.getLevel() == Level.WARNING
                                                && r.getThrown() instanceof Bug));
    }
}
