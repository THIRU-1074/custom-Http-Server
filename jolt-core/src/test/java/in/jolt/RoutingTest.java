package in.jolt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import in.jolt.RawClient.Reply;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class RoutingTest {

    private Server server;

    @AfterEach
    void stop() {
        if (server != null) {
            server.close();
        }
    }

    private Reply call(App app, String method, String path, String body) throws IOException {
        server = app.listen(0);
        try (var c = new RawClient(server.port())) {
            return c.request(method, path, body);
        }
    }

    @Test
    void middlewareChainRunsEveryMiddlewareInOrderThenTheHandler() throws IOException {
        List<String> trace = new ArrayList<>();
        App app = Jolt.create();
        app.use(
                (req, res, next) -> {
                    trace.add("a");
                    next.run();
                    trace.add("a-after");
                });
        app.use(
                (req, res, next) -> {
                    trace.add("b");
                    next.run();
                });
        app.use(
                "/x",
                (req, res, next) -> {
                    trace.add("c");
                    next.run();
                });
        app.get(
                "/x/y",
                (req, res) -> {
                    trace.add("handler");
                    res.text("ok");
                });

        Reply r = call(app, "GET", "/x/y", "");
        assertEquals("ok", r.text());
        assertEquals(List.of("a", "b", "c", "handler", "a-after"), trace);
    }

    @Test
    void middlewareScopedToAPrefixDoesNotRunElsewhere() throws IOException {
        List<String> trace = new ArrayList<>();
        App app = Jolt.create();
        app.use(
                "/admin",
                (req, res, next) -> {
                    trace.add("admin");
                    next.run();
                });
        app.get("/adminx", (req, res) -> res.text("plain"));

        assertEquals("plain", call(app, "GET", "/adminx", "").text());
        assertEquals(List.of(), trace);
    }

    @Test
    void middlewareCanShortCircuit() throws IOException {
        App app = Jolt.create();
        app.use((req, res, next) -> res.status(401).text("no"));
        app.get("/", (req, res) -> res.text("secret"));

        Reply r = call(app, "GET", "/", "");
        assertEquals(401, r.status());
        assertEquals("no", r.text());
    }

    @Test
    void putUsesItsOwnRouteTable() throws IOException {
        App app = Jolt.create();
        app.post("/r", (req, res) -> res.text("post"));
        app.put("/r", (req, res) -> res.text("put"));

        server = app.listen(0);
        try (var c = new RawClient(server.port())) {
            assertEquals("put", c.request("PUT", "/r", "").text());
            assertEquals("post", c.request("POST", "/r", "").text());
        }
    }

    @Test
    void deletePatchAndOptionsAreSupported() throws IOException {
        App app = Jolt.create();
        app.delete("/r/:id", (req, res) -> res.text("deleted " + req.param("id")));
        app.patch("/r/:id", (req, res) -> res.text("patched " + req.param("id")));
        app.get("/r/:id", (req, res) -> res.text("got"));
        app.options("/o", (req, res) -> res.status(200).text("custom"));
        app.head("/h", (req, res) -> res.text("head"));

        server = app.listen(0);
        try (var c = new RawClient(server.port())) {
            assertEquals("deleted 7", c.request("DELETE", "/r/7", "").text());
            assertEquals("patched 7", c.request("PATCH", "/r/7", "").text());
            assertEquals("custom", c.request("OPTIONS", "/o", "").text());
            Reply allow = c.request("OPTIONS", "/r/7", "");
            assertEquals(204, allow.status());
            assertEquals("DELETE, GET, PATCH", allow.header("Allow"));
        }
    }

    @Test
    void unsupportedMethodOnKnownPathIs405WithAllow() throws IOException {
        App app = Jolt.create();
        app.get("/only", (req, res) -> res.text("x"));

        Reply r = call(app, "POST", "/only", "");
        assertEquals(405, r.status());
        assertEquals("GET", r.header("Allow"));
    }

    @Test
    void unknownPathIs404() throws IOException {
        Reply r = call(Jolt.create(), "GET", "/nope", "");
        assertEquals(404, r.status());
        assertEquals("No route GET /nope", r.text());
    }

    @Test
    void headReusesGetRouteAndSendsNoBody() throws IOException {
        App app = Jolt.create();
        app.get("/g", (req, res) -> res.text("body"));

        server = app.listen(0);
        try (var c = new RawClient(server.port())) {
            c.send("HEAD /g HTTP/1.1\r\nHost: t\r\n\r\n");
            Reply r = c.readHeadOnly();
            assertEquals(200, r.status());
            assertEquals("4", r.header("Content-Length"));
            assertEquals("body", c.get("/g").text());
        }
    }

    @Test
    void longestMountPrefixWinsRegardlessOfRegistrationOrder() throws IOException {
        for (boolean shortFirst : new boolean[] {true, false}) {
            Router outer = new Router().get("/x", (req, res) -> res.text("outer-x"));
            Router inner = new Router().get("/x", (req, res) -> res.text("inner-x"));
            App app = Jolt.create();
            if (shortFirst) {
                app.use("/a", outer);
                app.use("/a/b", inner);
            } else {
                app.use("/a/b", inner);
                app.use("/a", outer);
            }

            server = app.listen(0);
            try (var c = new RawClient(server.port())) {
                assertEquals("inner-x", c.get("/a/b/x").text(), "shortFirst=" + shortFirst);
                assertEquals("outer-x", c.get("/a/x").text(), "shortFirst=" + shortFirst);
            }
            server.close();
        }
    }

    @Test
    void mountFallsThroughToShorterMountThenOwnRoutes() throws IOException {
        Router a = new Router().get("/only-in-a", (req, res) -> res.text("a"));
        Router ab = new Router().get("/only-in-ab", (req, res) -> res.text("ab"));
        App app = Jolt.create();
        app.use("/a", a);
        app.use("/a/b", ab);
        app.get("/a/b/own", (req, res) -> res.text("own"));

        server = app.listen(0);
        try (var c = new RawClient(server.port())) {
            assertEquals("ab", c.get("/a/b/only-in-ab").text());
            assertEquals("own", c.get("/a/b/own").text());
            assertEquals(404, c.get("/a/b/missing").status());
        }
    }

    @Test
    void mountedRouterSeesPathParamsAndRoot() throws IOException {
        Router users = new Router();
        users.get("/", (req, res) -> res.text("list"));
        users.get("/:id", (req, res) -> res.text("user " + req.param("id")));
        App app = Jolt.create();
        app.use("/users/", users);

        server = app.listen(0);
        try (var c = new RawClient(server.port())) {
            assertEquals("list", c.get("/users").text());
            assertEquals("user 5", c.get("/users/5").text());
        }
    }

    @Test
    void pathParamsQueryAndWildcard() throws IOException {
        App app = Jolt.create();
        app.get(
                "/p/:a/:b",
                (req, res) ->
                        res.text(
                                req.param("a")
                                        + "|"
                                        + req.param("b")
                                        + "|"
                                        + req.query("x")
                                        + "|"
                                        + req.query("missing")));
        app.get("/files/*", (req, res) -> res.text(req.param("*")));

        server = app.listen(0);
        try (var c = new RawClient(server.port())) {
            assertEquals("a b|c|1 2|null", c.get("/p/a%20b/c?x=1+2").text());
            assertEquals("deep/er/f.txt", c.get("/files/deep/er/f.txt").text());
        }
        assertNull(null);
    }
}
