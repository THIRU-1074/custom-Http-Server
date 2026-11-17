package in.jolt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import in.jolt.test.JoltTest;
import java.io.IOException;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ProblemJsonTest {

    private static final String PROBLEM = "application/problem+json; charset=utf-8";

    record Fault(String detail, int code) {}

    private static App app(boolean on) {
        App app = Jolt.create(JoltConfig.defaults().problemJson(on));
        app.get("/bad", (req, res) -> Validate.check(r -> r.notBlank("a", "").notBlank("b", " ")));
        app.get("/boom", (req, res) -> Integer.parseInt("x"));
        app.get(
                "/teapot",
                (req, res) -> {
                    throw new HttpException(418, "short and stout");
                });
        app.get("/cookie", (req, res) -> res.cookie("a", "b").status(204));
        return app;
    }

    @Test
    void offByDefaultKeepsPlainTextAndPlainValidationJson() {
        JoltTest t = JoltTest.of(Jolt.create());
        assertFalse(JoltConfig.defaults().problemJson());
        t.get("/nope").expect(404).header("Content-Type", "text/plain; charset=utf-8");
    }

    @Test
    void httpExceptionBecomesAProblemDocument() {
        JoltTest.of(app(true))
                .get("/teapot")
                .expect(418)
                .header("Content-Type", PROBLEM)
                .body(
                        "{\"type\":\"about:blank\",\"title\":\"Unknown\",\"status\":418,"
                                + "\"detail\":\"short and stout\"}");
    }

    @Test
    void unknownRouteAndInternalErrorUseTheStatusReasonAsTitle() {
        JoltTest t = JoltTest.of(app(true));
        t.get("/nope")
                .expect(404)
                .header("Content-Type", PROBLEM)
                .bodyContains("\"title\":\"Not Found\"")
                .bodyContains("\"detail\":\"No route GET /nope");
        t.get("/boom")
                .expect(500)
                .header("Content-Type", PROBLEM)
                .body(
                        "{\"type\":\"about:blank\",\"title\":\"Internal Server Error\","
                                + "\"status\":500,\"detail\":\"Internal Server Error\"}");
    }

    @Test
    void validationAddsAnErrorsMember() {
        JoltTest.of(app(true))
                .get("/bad")
                .expect(422)
                .header("Content-Type", PROBLEM)
                .body(
                        "{\"type\":\"about:blank\",\"title\":\"Unprocessable Entity\","
                                + "\"status\":422,\"detail\":\"Validation failed\","
                                + "\"errors\":{\"a\":\"must not be blank\","
                                + "\"b\":\"must not be blank\"}}");
    }

    @Test
    void mappedRepliesAreWrappedAndTheirMembersMerged() {
        App app = app(true);
        app.onError(IllegalStateException.class, e -> Reply.status(409, Map.of("order", 7)));
        app.onError(IllegalArgumentException.class, e -> Reply.status(400, e.getMessage()));
        app.onError(UnsupportedOperationException.class, e -> Reply.status(503, null));
        app.onError(ArithmeticException.class, e -> Reply.status(402, new Fault("pay", 9)));
        app.onError(
                ClassCastException.class,
                e -> Reply.status(400, Map.of("title", "Custom", "detail", "mine")));
        app.onError(ArrayStoreException.class, e -> Reply.status(400, java.util.List.of(1)));
        app.get(
                "/m2",
                (req, res) -> {
                    throw new IllegalStateException();
                });
        app.get(
                "/m3",
                (req, res) -> {
                    throw new IllegalArgumentException("bad input");
                });
        app.get(
                "/m4",
                (req, res) -> {
                    throw new UnsupportedOperationException();
                });
        app.get(
                "/m5",
                (req, res) -> {
                    throw new ArithmeticException();
                });
        app.get(
                "/m6",
                (req, res) -> {
                    throw new ClassCastException();
                });
        app.get(
                "/m7",
                (req, res) -> {
                    throw new ArrayStoreException();
                });
        JoltTest t = JoltTest.of(app);
        t.get("/m2")
                .expect(409)
                .header("Content-Type", PROBLEM)
                .body(
                        "{\"type\":\"about:blank\",\"title\":\"Conflict\",\"status\":409,"
                                + "\"order\":7}");
        t.get("/m3")
                .expect(400)
                .bodyContains("\"detail\":\"bad input\"")
                .bodyContains("\"title\":\"Bad Request\"");
        t.get("/m4")
                .expect(503)
                .body(
                        "{\"type\":\"about:blank\",\"title\":\"Service Unavailable\","
                                + "\"status\":503}");
        t.get("/m5")
                .expect(402)
                .bodyContains("\"detail\":\"pay\"")
                .bodyContains("\"code\":9")
                .bodyContains("\"status\":402");
        t.get("/m6")
                .expect(400)
                .bodyContains("\"title\":\"Custom\"")
                .bodyContains("\"detail\":\"mine\"")
                .bodyContains("\"status\":400");
        t.get("/m7").expect(400).header("Content-Type", PROBLEM).bodyContains("\"detail\":\"[1]\"");
    }

    @Test
    void validationMapperStillApplies() {
        App app = app(true);
        app.onError(
                ValidationException.class,
                e -> Reply.status(422, Map.of("errors", e.errors(), "extra", true)));
        JoltTest.of(app)
                .get("/bad")
                .expect(422)
                .header("Content-Type", PROBLEM)
                .bodyContains("\"extra\":true")
                .bodyContains("\"errors\":{\"a\"");
    }

    @Test
    void middlewareHeadersSurviveAndSuccessIsUntouched() {
        App app = app(true);
        app.use(
                (req, res, next) -> {
                    res.header("X-Trace", "t1");
                    next.run();
                });
        JoltTest t = JoltTest.of(app);
        t.get("/teapot").expect(418).header("X-Trace", "t1");
        t.get("/cookie").expect(204).header("X-Trace", "t1");
    }

    @Test
    void protocolErrorsAreProblemsToo() throws IOException {
        try (Server s = app(true).listen(0);
                var c = new RawClient(s.port())) {
            c.send("BROKEN\r\n\r\n");
            RawClient.Reply r = c.read();
            assertEquals(400, r.status());
            assertEquals(PROBLEM, r.header("Content-Type"));
            assertTrue(r.text().contains("\"status\":400"));
        }
    }

    @Test
    void connectionLimitRejectionIsAProblemToo() throws IOException {
        App app = Jolt.create(JoltConfig.defaults().problemJson(true).maxConnections(1));
        try (Server s = app.listen(0);
                var first = new RawClient(s.port())) {
            first.send("GET /x HTTP/1.1\r\nHost: t\r\n\r\n");
            first.read();
            try (var second = new RawClient(s.port())) {
                RawClient.Reply r = second.read();
                assertEquals(503, r.status());
                assertEquals(PROBLEM, r.header("Content-Type"));
            }
        }
    }

    private static final JsonMapper BROKEN =
            new JsonMapper() {
                @Override
                public byte[] write(Object value) {
                    throw new IllegalStateException("write");
                }

                @Override
                public <T> T read(byte[] json, Class<T> type) {
                    throw new IllegalArgumentException("read");
                }
            };

    @Test
    void rejectionsSurviveAThrowingMapperAndTheServerKeepsAccepting() throws IOException {
        App app =
                Jolt.create(
                        JoltConfig.defaults()
                                .jsonMapper(BROKEN)
                                .problemJson(true)
                                .maxConnections(1));
        app.get("/x", (req, res) -> res.text("ok"));
        try (Server s = app.listen(0);
                var first = new RawClient(s.port())) {
            for (int i = 0; i < 2; i++) {
                try (var extra = new RawClient(s.port())) {
                    RawClient.Reply r = extra.read();
                    assertEquals(503, r.status());
                    assertEquals(PROBLEM, r.header("Content-Type"));
                    assertEquals(
                            "{\"type\":\"about:blank\",\"title\":\"Service Unavailable\","
                                    + "\"status\":503,\"detail\":\"Server busy\"}",
                            r.text());
                }
            }
            first.send("GET /x HTTP/1.1\r\nHost: t\r\n\r\n");
            assertEquals("ok", first.read().text());
        }
    }

    @Test
    void protocolErrorsSurviveAThrowingMapper() throws IOException {
        for (boolean problem : new boolean[] {true, false}) {
            App app = Jolt.create(JoltConfig.defaults().jsonMapper(BROKEN).problemJson(problem));
            try (Server s = app.listen(0);
                    var c = new RawClient(s.port())) {
                c.send("BROKEN\r\n\r\n");
                RawClient.Reply r = c.read();
                assertEquals(400, r.status());
                assertTrue(
                        r.header("Content-Type")
                                .startsWith(problem ? "application/problem" : "text/plain"));
            }
        }
    }

    @Test
    void mappedRepliesBelow400AreNotProblems() {
        App app = app(true);
        app.onError(IllegalStateException.class, e -> Reply.status(200, Map.of("ok", true)));
        app.onError(IllegalArgumentException.class, e -> Reply.status(302, null));
        app.get(
                "/s",
                (req, res) -> {
                    throw new IllegalStateException();
                });
        app.get(
                "/r",
                (req, res) -> {
                    throw new IllegalArgumentException();
                });
        JoltTest t = JoltTest.of(app);
        t.get("/s")
                .expect(200)
                .header("Content-Type", "application/json; charset=utf-8")
                .body("{\"ok\":true}");
        t.get("/r").expect(302).body("");
    }
}
