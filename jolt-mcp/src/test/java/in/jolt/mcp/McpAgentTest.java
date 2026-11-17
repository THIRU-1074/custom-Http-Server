package in.jolt.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import in.jolt.App;
import in.jolt.Jolt;
import in.jolt.RequestId;
import in.jolt.Server;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(30)
class McpAgentTest {

    private static final String TRACEPARENT =
            "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01";

    private final HttpClient http = HttpClient.newHttpClient();
    private final List<ToolCall> calls = new CopyOnWriteArrayList<>();
    private final AtomicLong now = new AtomicLong();
    private Server server;

    private void start(McpOptions options) {
        App app = Jolt.create();
        app.use(RequestId.middleware());
        app.get(
                        "/echo/:id",
                        (req, res) ->
                                res.text(req.param("id") + " " + req.id() + " " + req.traceId()))
                .tool("Echo");
        app.get("/other", (req, res) -> res.text("other")).tool("Other");
        app.get("/boom", (req, res) -> res.status(503).text("down")).tool("Boom");
        Mcp.expose(
                app,
                "/mcp",
                options.agent(req -> req.header("X-Agent")).audit(calls::add),
                now::get);
        server = app.listen(0);
    }

    @AfterEach
    void stop() {
        if (server != null) {
            server.close();
        }
    }

    private String call(String tool, String args, String... headers) throws Exception {
        String json =
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{\"name\":\""
                        + tool
                        + "\",\"arguments\":"
                        + args
                        + "}}";
        HttpRequest.Builder b =
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + "/mcp"))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(json));
        for (int i = 0; i < headers.length; i += 2) {
            b.header(headers[i], headers[i + 1]);
        }
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString()).body();
    }

    private static boolean isError(String body) {
        return body.contains("\"isError\":true");
    }

    @Test
    void auditsEachCallWithoutArgumentsByDefault() throws Exception {
        start(McpOptions.defaults());
        call(
                "get_echo_id",
                "{\"id\":\"7\",\"secret\":\"s3\"}",
                "X-Agent",
                "bot-1",
                "User-Agent",
                "inspector/1.0",
                "X-Request-Id",
                "rid-9");
        assertEquals(1, calls.size());
        ToolCall c = calls.get(0);
        assertEquals("bot-1", c.agent());
        assertEquals("inspector/1.0", c.client());
        assertEquals("get_echo_id", c.tool());
        assertEquals("GET", c.method());
        assertEquals("/echo/:id", c.path());
        assertEquals(200, c.status());
        assertEquals("rid-9", c.requestId());
        assertNull(c.arguments());
        assertFalse(c.took().isNegative());
    }

    @Test
    void includesArgumentsWhenEnabled() throws Exception {
        start(McpOptions.defaults().auditArguments(true));
        call("get_echo_id", "{\"id\":\"7\",\"note\":null}");
        assertEquals("7", calls.get(0).arguments().get("id"));
        assertTrue(calls.get(0).arguments().containsKey("note"));
    }

    @Test
    void missingAgentIsAnonymous() throws Exception {
        start(McpOptions.defaults());
        call("get_other", "{}");
        assertEquals("anonymous", calls.get(0).agent());
    }

    @Test
    void auditsRouteFailuresAndBadArguments() throws Exception {
        start(McpOptions.defaults());
        call("get_boom", "{}");
        call("get_echo_id", "{}");
        assertEquals(503, calls.get(0).status());
        assertEquals(400, calls.get(1).status());
    }

    @Test
    void unknownToolsAreNotAudited() throws Exception {
        start(McpOptions.defaults());
        assertTrue(call("nope", "{}").contains("Unknown tool"));
        assertTrue(calls.isEmpty());
    }

    @Test
    void forwardsRequestIdAndTraceparentToTheRoute() throws Exception {
        start(McpOptions.defaults());
        String body =
                call(
                        "get_echo_id",
                        "{\"id\":\"7\"}",
                        "X-Request-Id",
                        "rid-9",
                        "traceparent",
                        TRACEPARENT);
        assertTrue(body.contains("7 rid-9 4bf92f3577b34da6a3ce929d0e0e4736"), body);
    }

    @Test
    void budgetsArePerAgentAndPerTool() throws Exception {
        start(McpOptions.defaults().budget("get_other", 1, Duration.ofSeconds(10)));
        assertFalse(isError(call("get_other", "{}", "X-Agent", "a")));
        String over = call("get_other", "{}", "X-Agent", "a");
        assertTrue(isError(over), over);
        assertTrue(over.contains("Budget exceeded for agent a on get_other; retry in 10s"), over);
        assertEquals(429, calls.get(1).status());
        assertFalse(isError(call("get_other", "{}", "X-Agent", "b")));
        assertFalse(isError(call("get_echo_id", "{\"id\":\"1\"}", "X-Agent", "a")));
    }

    @Test
    void budgetWindowResets() throws Exception {
        start(McpOptions.defaults().budget("get_other", 1, Duration.ofSeconds(10)));
        call("get_other", "{}");
        assertTrue(call("get_other", "{}").contains("Budget exceeded"));
        now.addAndGet(Duration.ofSeconds(10).toNanos());
        assertFalse(call("get_other", "{}").contains("Budget exceeded"));
    }

    @Test
    void starBudgetCountsEveryTool() throws Exception {
        start(McpOptions.defaults().budget("*", 2, Duration.ofMinutes(1)));
        call("get_other", "{}");
        call("get_echo_id", "{\"id\":\"1\"}");
        assertTrue(
                call("get_boom", "{}").contains("Budget exceeded for agent anonymous on get_boom"));
    }

    @Test
    void rejectsInvalidBudgets() {
        McpOptions o = McpOptions.defaults();
        assertThrows(IllegalArgumentException.class, () -> o.budget("", 1, Duration.ofSeconds(1)));
        assertThrows(IllegalArgumentException.class, () -> o.budget("*", 0, Duration.ofSeconds(1)));
        assertThrows(IllegalArgumentException.class, () -> o.budget("*", 1, Duration.ZERO));
        assertThrows(IllegalArgumentException.class, () -> o.budget("*", 1, null));
    }

    @Test
    void optionsKeepEveryFieldAcrossCopies() {
        McpOptions o =
                McpOptions.defaults()
                        .agent(r -> "x")
                        .audit(c -> {})
                        .auditArguments(true)
                        .budget("*", 1, Duration.ofSeconds(1))
                        .allowOrigins("http://a")
                        .name("n");
        assertTrue(o.auditArguments());
        assertEquals(1, o.budgets().size());
        assertEquals("n", o.name());
        assertTrue(o.allowedOrigins().contains("http://a"));
        assertTrue(o.agent() != null && o.audit() != null);
    }
}
