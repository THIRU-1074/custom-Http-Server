package in.jolt.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import in.jolt.App;
import in.jolt.HttpException;
import in.jolt.Jolt;
import in.jolt.Server;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(30)
class McpTest {

    record Todo(String title) {}

    private final HttpClient http = HttpClient.newHttpClient();
    private Server server;

    @BeforeEach
    void start() {
        App app = Jolt.create();
        app.use(
                "/todos",
                (req, res, next) -> {
                    if (!"Bearer ok".equals(req.header("Authorization"))) {
                        throw new HttpException(401, "Unauthorized");
                    }
                    next.run();
                });
        app.get("/todos/:id", (req, res) -> res.json(new Todo("todo " + req.param("id"))))
                .tool("Fetch a todo by id");
        app.post("/todos", (req, res) -> res.status(201).json(req.body(Todo.class)))
                .tool("Create a todo");
        app.get("/secret", (req, res) -> res.text("hidden"));
        Mcp.expose(app, "/mcp", McpOptions.defaults().allowOrigins("http://localhost:3000"));
        server = app.listen(0);
    }

    @AfterEach
    void stop() {
        server.close();
    }

    private HttpResponse<String> rpc(String json, String... headers) throws Exception {
        HttpRequest.Builder b =
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + "/mcp"))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(json));
        for (int i = 0; i < headers.length; i += 2) {
            b.header(headers[i], headers[i + 1]);
        }
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void initializeReportsToolsCapability() throws Exception {
        var r = rpc("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}");
        assertEquals(200, r.statusCode());
        assertTrue(r.body().contains("\"protocolVersion\":\"2025-06-18\""));
        assertTrue(r.body().contains("\"tools\":{}"));
    }

    @Test
    void listsOnlyTaggedRoutes() throws Exception {
        String body = rpc("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\"}").body();
        assertTrue(body.contains("\"name\":\"get_todos_id\""), body);
        assertTrue(body.contains("\"name\":\"post_todos\""), body);
        assertTrue(body.contains("Fetch a todo by id"));
        assertFalse(body.contains("secret"));
        assertFalse(body.contains("mcp"));
    }

    @Test
    void callDispatchesThroughMiddlewareWithForwardedAuth() throws Exception {
        String call =
                "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/call\",\"params\":"
                        + "{\"name\":\"get_todos_id\",\"arguments\":{\"id\":\"7\"}}}";
        String denied = rpc(call).body();
        assertTrue(denied.contains("\"isError\":true"), denied);
        assertTrue(denied.contains("HTTP 401"), denied);

        String ok = rpc(call, "Authorization", "Bearer ok").body();
        assertTrue(ok.contains("\"isError\":false"), ok);
        assertTrue(ok.contains("todo 7"), ok);
    }

    @Test
    void callPostsJsonBody() throws Exception {
        String ok =
                rpc(
                                "{\"jsonrpc\":\"2.0\",\"id\":4,\"method\":\"tools/call\",\"params\":"
                                        + "{\"name\":\"post_todos\",\"arguments\":{\"body\":{\"title\":\"x\"}}}}",
                                "Authorization",
                                "Bearer ok")
                        .body();
        assertTrue(ok.contains("\"isError\":false"), ok);
        assertTrue(ok.contains("title"), ok);
    }

    @Test
    void untaggedRouteIsNotCallable() throws Exception {
        String body =
                rpc("{\"jsonrpc\":\"2.0\",\"id\":5,\"method\":\"tools/call\",\"params\":"
                                + "{\"name\":\"get_secret\"}}")
                        .body();
        assertTrue(body.contains("\"code\":-32602"), body);
    }

    @Test
    void foreignOriginIsRejected() throws Exception {
        var r =
                rpc(
                        "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}",
                        "Origin",
                        "http://evil.example");
        assertEquals(403, r.statusCode());
        var ok =
                rpc(
                        "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}",
                        "Origin",
                        "http://localhost:3000");
        assertEquals(200, ok.statusCode());
    }

    @Test
    void notificationsGet202AndUnknownMethodsError() throws Exception {
        assertEquals(
                202,
                rpc("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}").statusCode());
        assertTrue(
                rpc("{\"jsonrpc\":\"2.0\",\"id\":9,\"method\":\"nope\"}")
                        .body()
                        .contains("-32601"));
        assertTrue(rpc("{bad").body().contains("-32700"));
        assertTrue(
                rpc("{\"jsonrpc\":\"2.0\",\"id\":9,\"method\":\"ping\"}")
                        .body()
                        .contains("result"));
    }

    @Test
    void mcpAuthMiddlewareGuardsTheEndpoint() throws Exception {
        server.close();
        App app = Jolt.create();
        app.get("/x", (req, res) -> res.text("x")).tool("x");
        Mcp.expose(
                app,
                "/mcp",
                McpOptions.defaults()
                        .auth(
                                (req, res, next) -> {
                                    if (req.header("X-Key") == null) {
                                        throw new HttpException(401, "key required");
                                    }
                                    next.run();
                                }));
        server = app.listen(0);
        assertEquals(401, rpc("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"}").statusCode());
        assertEquals(
                200,
                rpc("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"}", "X-Key", "1")
                        .statusCode());
    }

    @Test
    void getIsNotAllowed() throws IOException, InterruptedException {
        var r =
                http.send(
                        HttpRequest.newBuilder(
                                        URI.create("http://127.0.0.1:" + server.port() + "/mcp"))
                                .GET()
                                .build(),
                        HttpResponse.BodyHandlers.ofString());
        assertEquals(405, r.statusCode());
    }
}
