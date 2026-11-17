package in.jolt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import in.jolt.test.JoltTest;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RequestAwareErrorTest {

    private static final String PROBLEM = "application/problem+json; charset=utf-8";

    @Test
    void requestAwareMapperSeesTheRequest() {
        App app = Jolt.create();
        app.use(RequestId.middleware());
        app.get(
                "/x",
                (req, res) -> {
                    throw new IllegalStateException("taken");
                });
        app.onError(
                IllegalStateException.class,
                (e, req) -> Reply.status(409, Map.of("path", req.path(), "requestId", req.id())));
        JoltTest.of(app)
                .get("/x")
                .header("X-Request-Id", "abc-1")
                .expect(409)
                .jsonEquals("path", "/x")
                .jsonEquals("requestId", "abc-1");
    }

    @Test
    void singleArgumentMappersKeepWorkingNextToRequestAwareOnes() {
        App app = Jolt.create();
        app.get(
                "/a",
                (req, res) -> {
                    throw new IllegalStateException("a");
                });
        app.get(
                "/b",
                (req, res) -> {
                    throw new IllegalArgumentException("b");
                });
        app.onError(IllegalStateException.class, e -> Reply.status(409, Map.of("m", "one")));
        app.onError(
                IllegalArgumentException.class,
                (e, req) -> Reply.status(400, Map.of("m", req.method())));
        JoltTest t = JoltTest.of(app);
        t.get("/a").expect(409).jsonEquals("m", "one");
        t.get("/b").expect(400).jsonEquals("m", "GET");
    }

    @Test
    void problemBodiesCarryTheRequestIdWhenRequestIdIsInstalled() {
        App app = Jolt.create(JoltConfig.defaults().problemJson(true));
        app.use(RequestId.middleware());
        app.get(
                "/teapot",
                (req, res) -> {
                    throw new HttpException(418, "short");
                });
        app.get(
                "/conflict",
                (req, res) -> {
                    throw new IllegalStateException("x");
                });
        app.get("/boom", (req, res) -> Integer.parseInt("x"));
        app.onError(IllegalStateException.class, e -> Problem.conflict());
        JoltTest t = JoltTest.of(app);
        for (String path : new String[] {"/teapot", "/conflict", "/boom", "/nope"}) {
            t.get(path)
                    .header("X-Request-Id", "rid-7")
                    .send()
                    .header("Content-Type", PROBLEM)
                    .jsonEquals("requestId", "rid-7");
        }
        var generated = t.get("/nope").send();
        assertNotNull(generated.header("X-Request-Id"));
        assertEquals(generated.header("X-Request-Id"), generated.jsonField("requestId"));
    }

    @Test
    void aMappedRequestIdWinsAndNoIdMeansNoField() {
        App app = Jolt.create(JoltConfig.defaults().problemJson(true));
        app.use("/with", RequestId.middleware());
        app.get(
                "/with/x",
                (req, res) -> {
                    throw new IllegalStateException("x");
                });
        app.get(
                "/without",
                (req, res) -> {
                    throw new IllegalStateException("x");
                });
        app.onError(
                IllegalStateException.class,
                (e, req) ->
                        Reply.status(
                                409, req.id() == null ? Map.of() : Map.of("requestId", "mine")));
        JoltTest t = JoltTest.of(app);
        t.get("/with/x").expect(409).jsonEquals("requestId", "mine");
        assertFalse(t.get("/without").expect(409).jsonHas("requestId"));
    }

    @Test
    void plainTextErrorsAreUnchangedByRequestId() {
        App app = Jolt.create();
        app.use(RequestId.middleware());
        JoltTest.of(app)
                .get("/nope")
                .expect(404)
                .header("Content-Type", "text/plain; charset=utf-8")
                .body("No route GET /nope");
    }

    private static final class Problem {
        static Reply<Map<String, Object>> conflict() {
            return Reply.status(409, Map.of("title", "Conflict"));
        }
    }
}
