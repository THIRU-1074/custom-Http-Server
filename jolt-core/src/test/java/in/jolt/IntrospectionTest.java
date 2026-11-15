package in.jolt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import in.jolt.RawClient.Reply;
import in.jolt.internal.json.Json;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class IntrospectionTest {

    private Server server;

    @AfterEach
    void stop() {
        if (server != null) {
            server.close();
        }
    }

    private App app() {
        App app = Jolt.create();
        app.get("/users", (req, res) -> res.text("u"));
        app.get("/users/:id", (req, res) -> res.text("u"));
        app.post("/users", (req, res) -> res.text("u"));
        return app;
    }

    private Reply get(App app, String path) throws IOException {
        server = app.listen(0);
        try (var c = new RawClient(server.port())) {
            return c.request("GET", path, "");
        }
    }

    @Test
    void typoGetsDidYouMeanHint() throws IOException {
        Reply r = get(app(), "/usrs");
        assertEquals(404, r.status());
        assertEquals("No route GET /usrs, did you mean GET /users?", r.text());
    }

    @Test
    void typoInsideParamRouteSuggestsPattern() throws IOException {
        Reply r = get(app(), "/usrs/7");
        assertEquals("No route GET /usrs/7, did you mean GET /users/:id?", r.text());
    }

    @Test
    void farMissHasNoHint() throws IOException {
        assertEquals("No route GET /zzzzzzzz", get(app(), "/zzzzzzzz").text());
    }

    @Test
    void routesIncludeMountedRouters() {
        Router sub = new Router();
        sub.get("/ping", (req, res) -> res.text("pong"));
        App app = app();
        app.use("/api", sub);
        assertTrue(app.routes().contains(new Route("GET", "/api/ping", null)));
        assertEquals(4, app.routes().size());
    }

    @Test
    void routesEndpointIsOptIn() throws IOException {
        assertEquals(404, get(app(), "/_jolt/routes").status());
        stop();
        Reply r = get(app().exposeRoutes(), "/_jolt/routes");
        assertEquals(200, r.status());
        assertTrue(r.text().contains("\"path\":\"/users/:id\""));
    }

    @Test
    @SuppressWarnings("unchecked")
    void openApiDescribesPathsAndParams() {
        Map<String, Object> doc = (Map<String, Object>) Json.parse(app().openApi());
        assertEquals("3.1.0", doc.get("openapi"));
        Map<String, Object> paths = (Map<String, Object>) doc.get("paths");
        assertEquals(List.of("/users", "/users/{id}"), List.copyOf(paths.keySet()));
        Map<String, Object> item = (Map<String, Object>) paths.get("/users/{id}");
        Map<String, Object> get = (Map<String, Object>) item.get("get");
        assertEquals(1, ((List<?>) get.get("parameters")).size());
        assertEquals(
                List.of("get", "post"),
                List.copyOf(((Map<String, Object>) paths.get("/users")).keySet()));
    }

    @Test
    void invalidJsonBodyReportsPosition() throws IOException {
        App app = Jolt.create();
        app.post("/x", (req, res) -> res.json(req.body(Map.class)));
        server = app.listen(0);
        try (var c = new RawClient(server.port())) {
            Reply r = c.request("POST", "/x", "{\"a\": 1,, }");
            assertEquals(400, r.status());
            assertTrue(r.text().startsWith("Body is not valid JSON at char "));
        }
    }
}
