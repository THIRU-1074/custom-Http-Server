package in.jolt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import in.jolt.test.JoltTest;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

class MetricsTest {

    private static App app() {
        App app = Jolt.create();
        app.exposeMetrics();
        app.get("/ok", (req, res) -> res.text("ok"));
        app.get(
                "/slow",
                (req, res) -> {
                    Thread.sleep(30);
                    res.text("slow");
                });
        app.get(
                "/boom",
                (req, res) -> {
                    throw new IllegalStateException("secret-detail");
                });
        app.post("/echo", (req, res) -> res.send(req.body()));
        return app;
    }

    private static long value(String metrics, String series) {
        Matcher m =
                Pattern.compile("^" + Pattern.quote(series) + " (\\d+)$", Pattern.MULTILINE)
                        .matcher(metrics);
        assertTrue(m.find(), "missing series " + series + " in:\n" + metrics);
        return Long.parseLong(m.group(1));
    }

    @Test
    void healthReportsUp() {
        JoltTest.of(app()).get("/_jolt/health").expect(200).body("{\"status\":\"UP\"}");
    }

    @Test
    void countsRequestsByMethodAndStatusClass() {
        JoltTest t = JoltTest.of(app());
        t.get("/ok").expect(200);
        t.get("/ok").expect(200);
        t.get("/nowhere?token=hunter2").expect(404);
        t.get("/boom").expect(500);
        t.post("/echo").body(new byte[] {1, 2, 3, 4}).expect(200);
        t.delete("/ok").expect(405);
        String m = t.get("/_jolt/metrics").expect(200).text();
        assertEquals(2, value(m, "jolt_http_requests_total{method=\"GET\",class=\"2xx\"}"));
        assertEquals(1, value(m, "jolt_http_requests_total{method=\"GET\",class=\"4xx\"}"));
        assertEquals(1, value(m, "jolt_http_requests_total{method=\"GET\",class=\"5xx\"}"));
        assertEquals(1, value(m, "jolt_http_requests_total{method=\"POST\",class=\"2xx\"}"));
        assertEquals(1, value(m, "jolt_http_requests_total{method=\"DELETE\",class=\"4xx\"}"));
        assertEquals(4, value(m, "jolt_http_request_body_bytes_total"));
        assertTrue(value(m, "jolt_http_response_body_bytes_total") >= 4 + 2 + 2);
    }

    @Test
    void latencyHistogramIsCumulativeWithFixedBuckets() {
        JoltTest t = JoltTest.of(app());
        t.get("/ok").expect(200);
        t.get("/slow").expect(200);
        String m = t.get("/_jolt/metrics").expect(200).text();
        long count = value(m, "jolt_http_request_duration_seconds_count");
        assertEquals(2, count);
        assertEquals(count, value(m, "jolt_http_request_duration_seconds_bucket{le=\"+Inf\"}"));
        long fast = value(m, "jolt_http_request_duration_seconds_bucket{le=\"0.005\"}");
        long half = value(m, "jolt_http_request_duration_seconds_bucket{le=\"0.5\"}");
        assertTrue(fast < half, "30 ms request must land above the 5 ms bucket");
        assertEquals(2, half);
        assertTrue(m.contains("# TYPE jolt_http_request_duration_seconds histogram"));
        assertTrue(m.contains("jolt_http_request_duration_seconds_sum "));
    }

    @Test
    void exposesNoPathsQueriesOrErrorDetails() {
        JoltTest t = JoltTest.of(app());
        t.get("/nowhere?token=hunter2").expect(404);
        t.get("/boom").expect(500);
        String m = t.get("/_jolt/metrics").expect(200).text();
        assertFalse(m.contains("hunter2"));
        assertFalse(m.contains("nowhere"));
        assertFalse(m.contains("secret-detail"));
        assertFalse(m.contains("boom"));
    }

    @Test
    void metricsAreServedAsPrometheusText() {
        JoltTest.of(app())
                .get("/_jolt/metrics")
                .expect(200)
                .header("Content-Type", "text/plain; version=0.0.4; charset=utf-8");
    }

    @Test
    void disabledUnlessEnabled() {
        App plain = Jolt.create();
        plain.get("/ok", (req, res) -> res.text("ok"));
        JoltTest t = JoltTest.of(plain);
        t.get("/_jolt/metrics").expect(404);
        t.get("/_jolt/health").expect(404);
    }

    @Test
    void guardProtectsTheMetricsEndpoint() {
        App app = Jolt.create();
        app.exposeMetrics(
                (req, res, next) -> {
                    if ("let-me-in".equals(req.header("X-Key"))) {
                        next.run();
                    } else {
                        throw new HttpException(HttpStatus.UNAUTHORIZED, "no");
                    }
                });
        JoltTest t = JoltTest.of(app);
        t.get("/_jolt/metrics").expect(401);
        t.get("/_jolt/metrics").header("X-Key", "let-me-in").expect(200);
        t.get("/_jolt/health").expect(200);
    }

    @Test
    void countsTheStatusTheClientReceivedAfterErrorMappers() {
        App app = app();
        app.onError(HttpException.class, e -> new Reply<>(503, null));
        JoltTest t = JoltTest.of(app);
        t.get("/nowhere").expect(503);
        String m = t.get("/_jolt/metrics").expect(200).text();
        assertEquals(1, value(m, "jolt_http_requests_total{method=\"GET\",class=\"5xx\"}"));
        assertFalse(m.contains("class=\"4xx\""));
    }

    @Test
    void websocketsAndStreamsStayOutOfTheLatencyHistogram() throws Exception {
        App app = app();
        app.ws("/ws", WebSocketHandler.text((s, m) -> s.send(m)));
        app.get(
                "/sse",
                (req, res) -> {
                    res.sse().event("a", "b").close();
                });
        try (java.net.http.HttpClient client = java.net.http.HttpClient.newHttpClient();
                Server server = app.listen(0)) {
            java.net.http.WebSocket ws =
                    client.newWebSocketBuilder()
                            .buildAsync(
                                    java.net.URI.create("ws://127.0.0.1:" + server.port() + "/ws"),
                                    new java.net.http.WebSocket.Listener() {})
                            .get(5, java.util.concurrent.TimeUnit.SECONDS);
            try (RawClient s = new RawClient(server.port())) {
                s.send("GET /sse HTTP/1.1\r\nHost: t\r\nConnection: close\r\n\r\n");
                s.isClosedByPeer();
            }
            String m;
            try (RawClient c = new RawClient(server.port())) {
                m = c.get("/_jolt/metrics").text();
            }
            assertEquals(1, value(m, "jolt_websocket_connections_active"));
            assertEquals(1, value(m, "jolt_websocket_connections_total"));
            assertEquals(1, value(m, "jolt_streams_total"));
            assertEquals(0, value(m, "jolt_http_request_duration_seconds_count"));
            assertEquals(1, value(m, "jolt_http_requests_total{method=\"GET\",class=\"1xx\"}"));
            ws.abort();
        }
    }

    @Test
    void callingItTwiceInstallsItOnce() {
        App app = app();
        app.exposeMetrics();
        JoltTest t = JoltTest.of(app);
        t.get("/ok").expect(200);
        String m = t.get("/_jolt/metrics").expect(200).text();
        assertEquals(1, value(m, "jolt_http_requests_total{method=\"GET\",class=\"2xx\"}"));
        assertEquals(
                1, app.routes().stream().filter(r -> r.path().equals("/_jolt/metrics")).count());
    }

    @Test
    void enablingAfterListenStillCountsConnections() throws Exception {
        App app = Jolt.create();
        app.get("/ok", (req, res) -> res.text("ok"));
        try (Server server = app.listen(0)) {
            app.exposeMetrics();
            try (RawClient c = new RawClient(server.port())) {
                c.get("/ok");
            }
            try (RawClient c = new RawClient(server.port())) {
                String m = c.get("/_jolt/metrics").text();
                assertTrue(value(m, "jolt_connections_total") >= 1);
            }
        }
    }

    @Test
    void countsConnectionsOnARealServer() throws Exception {
        try (Server server = app().listen(0)) {
            try (RawClient c = new RawClient(server.port())) {
                String m = c.get("/_jolt/metrics").text();
                assertEquals(1, value(m, "jolt_connections_active"));
                assertEquals(1, value(m, "jolt_connections_total"));
            }
            long deadline = System.nanoTime() + 5_000_000_000L;
            long active;
            long total;
            do {
                try (RawClient c = new RawClient(server.port())) {
                    String m = c.get("/_jolt/metrics").text();
                    active = value(m, "jolt_connections_active");
                    total = value(m, "jolt_connections_total");
                }
                if (active == 1) {
                    break;
                }
                Thread.sleep(20);
            } while (System.nanoTime() < deadline);
            assertEquals(1, active, "first connection must be released");
            assertTrue(total >= 2);
        }
    }
}
