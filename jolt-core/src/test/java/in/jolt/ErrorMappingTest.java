package in.jolt;

import static org.junit.jupiter.api.Assertions.assertFalse;

import in.jolt.test.JoltTest;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ErrorMappingTest {

    static class AppError extends RuntimeException {
        AppError(String m) {
            super(m);
        }
    }

    static class NotFound extends AppError {
        NotFound(String m) {
            super(m);
        }
    }

    private static App app() {
        App app = Jolt.create();
        app.use(
                (req, res, next) -> {
                    res.header("X-Seen", "yes");
                    next.run();
                });
        app.get(
                "/nf",
                (req, res) -> {
                    throw new NotFound("secret-table users");
                });
        app.get(
                "/app",
                (req, res) -> {
                    throw new AppError("boom");
                });
        app.get(
                "/other",
                (req, res) -> {
                    throw new IllegalStateException("db password");
                });
        app.get("/http", p -> Integer.valueOf(throw403()));
        return app;
    }

    private static int throw403() {
        throw new HttpException(403, "forbidden");
    }

    @Test
    void mostSpecificMapperWinsRegardlessOfRegistrationOrder() {
        App a = app();
        a.onError(AppError.class, e -> Reply.status(400, Map.of("error", "app")));
        a.onError(NotFound.class, e -> Reply.status(404, Map.of("error", "missing")));
        JoltTest t = JoltTest.of(a);
        t.get("/nf").expect(404).bodyContains("missing");
        t.get("/app").expect(400).bodyContains("app");

        App b = app();
        b.onError(NotFound.class, e -> Reply.status(404, Map.of("error", "missing")));
        b.onError(AppError.class, e -> Reply.status(400, Map.of("error", "app")));
        JoltTest.of(b).get("/nf").expect(404).bodyContains("missing");
    }

    @Test
    void mappedReplyIsJsonAndKeepsMiddlewareHeaders() {
        App a = app();
        a.onError(NotFound.class, e -> Reply.status(404, Map.of("error", "missing")));
        JoltTest.of(a)
                .get("/nf")
                .expect(404)
                .header("x-seen", "yes")
                .header("content-type", "application/json; charset=utf-8");
    }

    @Test
    void mapperOutputDecidesTheBodyNotTheExceptionMessage() {
        App a = app();
        a.onError(NotFound.class, e -> Reply.status(404, "gone"));
        String body = JoltTest.of(a).get("/nf").expect(404).text();
        assertFalse(body.contains("secret-table"));
    }

    @Test
    void unmappedExceptionStaysGeneric500() {
        App a = app();
        a.onError(NotFound.class, e -> Reply.status(404, "gone"));
        JoltTest.of(a)
                .get("/other")
                .expect(500)
                .body("Internal Server Error")
                .header("x-seen", "yes");
    }

    @Test
    void httpExceptionKeepsWorkingUnlessMapped() {
        App a = app();
        a.onError(NotFound.class, e -> Reply.status(404, "gone"));
        JoltTest.of(a).get("/http").expect(403).body("forbidden");
    }

    @Test
    void failingMapperFallsBackToGeneric500() {
        App a = app();
        a.onError(
                NotFound.class,
                e -> {
                    throw new IllegalStateException("mapper broke");
                });
        JoltTest.of(a).get("/nf").expect(500).body("Internal Server Error");
    }

    @Test
    void mapperMayHandleHttpExceptionToo() {
        App a = app();
        a.onError(
                HttpException.class, e -> Reply.status(e.status(), Map.of("msg", e.getMessage())));
        JoltTest.of(a).get("/http").expect(403).bodyContains("\"msg\"");
    }

    @Test
    void mapperWorksForTypedRoutes() {
        App a = app();
        a.onError(NotFound.class, e -> Reply.status(404, "gone"));
        a.get(
                "/typed",
                p -> {
                    throw new NotFound("x");
                });
        JoltTest.of(a).get("/typed").expect(404).body("\"gone\"");
    }
}
