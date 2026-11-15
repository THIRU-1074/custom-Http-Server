package in.jolt;

import static org.junit.jupiter.api.Assertions.assertTrue;

import in.jolt.test.JoltTest;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class MapperFailureTest {

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
    void mapperThrowingHttpExceptionIsHonoured() {
        App a = app();
        a.onError(
                Bug.class,
                e -> {
                    throw new HttpException(422, "bad input");
                });
        JoltTest.of(a).get("/bug").expect(422).body("bad input");
    }

    @Test
    void mapperThrowingAnythingElseGivesLoggedGeneric500() {
        App a = app();
        a.onError(
                Bug.class,
                e -> {
                    throw new IllegalArgumentException("mapper broke");
                });
        JoltTest.of(a).get("/bug").expect(500).body("Internal Server Error");
        assertTrue(logs.stream().anyMatch(r -> r.getThrown() != null));
    }

    @Test
    void stackOverflowInMapperOrSerializationNeverEscapes() {
        App a = app();
        a.onError(Bug.class, e -> recurse());
        JoltTest.of(a).get("/bug").expect(500).body("Internal Server Error");

        App b = app();
        b.onError(
                Bug.class,
                e ->
                        Reply.status(
                                400,
                                new Object() {
                                    @Override
                                    public String toString() {
                                        throw new StackOverflowError();
                                    }
                                }));
        JoltTest.of(b).get("/bug").expect(500).body("Internal Server Error");
    }

    private static Reply<?> recurse() {
        return recurse();
    }
}
