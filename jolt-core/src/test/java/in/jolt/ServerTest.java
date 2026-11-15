package in.jolt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import in.jolt.RawClient.Reply;
import java.io.IOException;
import java.net.ConnectException;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(30)
class ServerTest {

    record Todo(String title, int priority) {}

    private Server server;

    @AfterEach
    void stop() {
        if (server != null) {
            server.close();
        }
    }

    private App echoApp(JoltConfig config) {
        App app = Jolt.create(config);
        app.get("/", (req, res) -> res.text("hello"));
        app.post(
                "/echo",
                (req, res) ->
                        res.send(req.body()).header("Content-Type", "application/octet-stream"));
        app.post("/todos", (req, res) -> res.status(201).json(req.body(Todo.class)));
        app.get(
                "/boom",
                (req, res) -> {
                    throw new IllegalStateException("intentional test failure");
                });
        app.get(
                "/teapot",
                (req, res) -> {
                    throw new HttpException(418, "short and stout");
                });
        return app;
    }

    @Test
    void keepAliveServesSeveralRequestsOnOneConnection() throws IOException {
        server = echoApp(JoltConfig.defaults()).listen(0);
        try (var c = new RawClient(server.port())) {
            for (int i = 0; i < 3; i++) {
                Reply r = c.get("/");
                assertEquals("hello", r.text());
                assertEquals("keep-alive", r.header("Connection"));
            }
        }
    }

    @Test
    void pipelinedRequestsAreAnsweredInOrder() throws IOException {
        App app = Jolt.create();
        app.get(
                "/slow",
                (req, res) -> {
                    Thread.sleep(100);
                    res.text("slow");
                });
        app.get("/fast", (req, res) -> res.text("fast"));
        server = app.listen(0);
        try (var c = new RawClient(server.port())) {
            c.send(
                    "GET /slow HTTP/1.1\r\nHost: t\r\n\r\n"
                            + "GET /fast HTTP/1.1\r\nHost: t\r\n\r\n"
                            + "GET /slow HTTP/1.1\r\nHost: t\r\n\r\n");
            assertEquals("slow", c.read().text());
            assertEquals("fast", c.read().text());
            assertEquals("slow", c.read().text());
        }
    }

    @Test
    void http10ClosesAfterTheResponseUnlessKeepAliveRequested() throws IOException {
        server = echoApp(JoltConfig.defaults()).listen(0);
        try (var c = new RawClient(server.port())) {
            Reply r = c.send("GET / HTTP/1.0\r\n\r\n").read();
            assertEquals("close", r.header("Connection"));
            assertTrue(c.isClosedByPeer());
        }
        try (var c = new RawClient(server.port())) {
            Reply r = c.send("GET / HTTP/1.0\r\nConnection: keep-alive\r\n\r\n").read();
            assertEquals("keep-alive", r.header("Connection"));
            assertEquals("hello", c.get("/").text());
        }
    }

    @Test
    void connectionCloseHeaderIsHonoured() throws IOException {
        server = echoApp(JoltConfig.defaults()).listen(0);
        try (var c = new RawClient(server.port())) {
            Reply r = c.send("GET / HTTP/1.1\r\nConnection: close\r\n\r\n").read();
            assertEquals("close", r.header("Connection"));
            assertTrue(c.isClosedByPeer());
        }
    }

    @Test
    void bodyIsReadByContentLengthEvenWhenSplitAcrossWrites() throws IOException {
        server = echoApp(JoltConfig.defaults()).listen(0);
        byte[] payload = new byte[3000];
        for (int i = 0; i < payload.length; i++) {
            payload[i] = (byte) i;
        }
        try (var c = new RawClient(server.port())) {
            c.send("POST /echo HTTP/1.1\r\nHost: t\r\nContent-Length: 3000\r\n\r\n");
            c.send(java.util.Arrays.copyOfRange(payload, 0, 1000));
            sleep(100);
            c.send(java.util.Arrays.copyOfRange(payload, 1000, 3000));
            Reply r = c.read();
            assertEquals(200, r.status());
            org.junit.jupiter.api.Assertions.assertArrayEquals(payload, r.body());
            assertEquals("hello", c.get("/").text());
        }
    }

    @Test
    void chunkedRequestBodiesAreDecoded() throws IOException {
        server = echoApp(JoltConfig.defaults()).listen(0);
        try (var c = new RawClient(server.port())) {
            Reply r =
                    c.send(
                                    "POST /echo HTTP/1.1\r\nHost: t\r\nTransfer-Encoding: chunked\r\n\r\n"
                                            + "4\r\nWiki\r\n5\r\npedia\r\n0\r\n\r\n")
                            .read();
            assertEquals("Wikipedia", r.text());
        }
    }

    @Test
    void jsonBodiesBindToRecordsAndBadJsonIs400() throws IOException {
        server = echoApp(JoltConfig.defaults()).listen(0);
        try (var c = new RawClient(server.port())) {
            Reply ok = c.request("POST", "/todos", "{\"title\":\"milk\",\"priority\":2}");
            assertEquals(201, ok.status());
            assertEquals("{\"title\":\"milk\",\"priority\":2}", ok.text());
            assertEquals("application/json; charset=utf-8", ok.header("Content-Type"));

            Reply bad = c.request("POST", "/todos", "{\"title\": }");
            assertEquals(400, bad.status());
            assertEquals("Body is not valid JSON at char 10", bad.text());

            assertEquals(400, c.request("POST", "/todos", "").status());
            assertEquals(
                    "Expected a number for Todo.priority",
                    c.request("POST", "/todos", "{\"priority\":\"x\"}").text());
        }
    }

    @Test
    void handlerExceptionYieldsGeneric500AndTheConnectionStaysUsable() throws IOException {
        server = echoApp(JoltConfig.defaults()).listen(0);
        try (var c = new RawClient(server.port())) {
            Reply r = c.get("/boom");
            assertEquals(500, r.status());
            assertEquals("Internal Server Error", r.text());
            assertEquals("hello", c.get("/").text());
        }
    }

    @Test
    void httpExceptionMapsToItsStatusAndMessage() throws IOException {
        server = echoApp(JoltConfig.defaults()).listen(0);
        try (var c = new RawClient(server.port())) {
            Reply r = c.get("/teapot");
            assertEquals(418, r.status());
            assertEquals("short and stout", r.text());
        }
    }

    @Test
    void middlewareThatThrowsAfterPartialResponseStillGets500() throws IOException {
        App app = Jolt.create();
        app.use(
                (req, res, next) -> {
                    res.header("X-Partial", "1").text("half");
                    throw new IllegalStateException("nope");
                });
        server = app.listen(0);
        try (var c = new RawClient(server.port())) {
            Reply r = c.get("/");
            assertEquals(500, r.status());
            assertEquals(null, r.header("X-Partial"));
        }
    }

    @Test
    void handlerThatNeverRespondsStillGetsAnEmptyResponse() throws IOException {
        App app = Jolt.create();
        app.get("/silent", (req, res) -> {});
        app.get("/nocontent", (req, res) -> res.status(204));
        server = app.listen(0);
        try (var c = new RawClient(server.port())) {
            Reply r = c.get("/silent");
            assertEquals(200, r.status());
            assertEquals("0", r.header("Content-Length"));
            Reply nc = c.send("GET /nocontent HTTP/1.1\r\n\r\n").readHeadOnly();
            assertEquals(204, nc.status());
            assertEquals(null, nc.header("Content-Length"));
        }
    }

    @Test
    void cookiesAreReadFromTheCookieHeader() throws IOException {
        App app = Jolt.create();
        app.get(
                "/me",
                (req, res) ->
                        res.text(
                                req.cookie("sid")
                                        + "/"
                                        + req.cookie("theme")
                                        + "/"
                                        + req.cookie("x")));
        server = app.listen(0);
        try (var c = new RawClient(server.port())) {
            Reply r = c.send("GET /me HTTP/1.1\r\nCookie: sid=abc; theme=dark\r\n\r\n").read();
            assertEquals("abc/dark/null", r.text());
        }
    }

    @Test
    void eachSetCookieIsItsOwnHeader() throws IOException {
        App app = Jolt.create();
        app.get(
                "/login",
                (req, res) ->
                        res.cookie("a", "1")
                                .cookie("b", "2", "Path=/x")
                                .cookie("c", "3", "")
                                .text("ok"));
        server = app.listen(0);
        try (var c = new RawClient(server.port())) {
            Reply r = c.get("/login");
            assertEquals(
                    List.of("a=1; Path=/; HttpOnly; SameSite=Lax", "b=2; Path=/x", "c=3"),
                    r.headers().get("Set-Cookie"));
        }
    }

    @Test
    void crLfInResponseHeadersAndCookiesIsRejected() throws IOException {
        App app = Jolt.create();
        app.get("/go", (req, res) -> res.header("Location", req.query("to")).status(302));
        app.get("/c", (req, res) -> res.cookie("sid", req.query("v")).text("ok"));
        server = app.listen(0);
        try (var c = new RawClient(server.port())) {
            Reply r = c.get("/go?to=%0d%0aSet-Cookie:%20evil=1");
            assertEquals(500, r.status());
            assertEquals(null, r.header("Set-Cookie"));
            assertEquals(500, c.get("/c?v=a%0d%0aX:%20y").status());
            assertEquals(200, c.get("/c?v=abc").status());
        }
        Response res = new Response(JoltConfig.defaults().jsonMapper());
        assertThrows(IllegalArgumentException.class, () -> res.header("X\r\nY", "v"));
        assertThrows(IllegalArgumentException.class, () -> res.header("X:Y", "v"));
        assertThrows(IllegalArgumentException.class, () -> res.header("X", "a\nb"));
        assertThrows(IllegalArgumentException.class, () -> res.cookie("a", "b", "Path=/\r\nX: y"));
    }

    @Test
    void responseHelpersSetContentTypes() throws IOException {
        App app = Jolt.create();
        app.get("/html", (req, res) -> res.html("<p>x</p>"));
        app.get("/bytes", (req, res) -> res.send(new byte[] {1, 2}));
        app.get(
                "/override",
                (req, res) ->
                        res.header("Content-Type", "text/csv")
                                .header("content-type", "text/x")
                                .text("a,b"));
        app.get(
                "/user-framing",
                (req, res) ->
                        res.header("Content-Length", "999")
                                .header("Connection", "upgrade")
                                .text("ok"));
        server = app.listen(0);
        try (var c = new RawClient(server.port())) {
            assertEquals("text/html; charset=utf-8", c.get("/html").header("Content-Type"));
            assertEquals("application/octet-stream", c.get("/bytes").header("Content-Type"));
            assertEquals("text/x", c.get("/override").header("Content-Type"));
            Reply r = c.get("/user-framing");
            assertEquals("2", r.header("Content-Length"));
            assertEquals("keep-alive", r.header("Connection"));
            assertNotNull(r.header("Date"));
        }
    }

    @Test
    void oversizedBodyIs413AndOversizedHeadersAre431() throws IOException {
        server = echoApp(JoltConfig.defaults().maxBodyBytes(10).maxHeaderBytes(200)).listen(0);
        try (var c = new RawClient(server.port())) {
            Reply r = c.send("POST /echo HTTP/1.1\r\nContent-Length: 11\r\n\r\n").read();
            assertEquals(413, r.status());
            assertTrue(c.isClosedByPeer());
        }
        try (var c = new RawClient(server.port())) {
            Reply r = c.send("GET / HTTP/1.1\r\nX: " + "a".repeat(300) + "\r\n\r\n").read();
            assertEquals(431, r.status());
        }
    }

    @Test
    void requestLineOver8KbIs414AndMoreThan100HeadersIs431() throws IOException {
        server = echoApp(JoltConfig.defaults()).listen(0);
        try (var c = new RawClient(server.port())) {
            Reply r = c.send("GET /" + "a".repeat(9000) + " HTTP/1.1\r\n\r\n").read();
            assertEquals(414, r.status());
            assertTrue(c.isClosedByPeer());
        }
        StringBuilder many = new StringBuilder("GET / HTTP/1.1\r\n");
        for (int i = 0; i < 101; i++) {
            many.append("X-").append(i).append(": v\r\n");
        }
        try (var c = new RawClient(server.port())) {
            assertEquals(431, c.send(many.append("\r\n").toString()).read().status());
        }
        assertEquals(8 * 1024, JoltConfig.defaults().maxRequestLineBytes());
        assertEquals(100, JoltConfig.defaults().maxHeaderCount());
    }

    @Test
    void malformedRequestIs400AndClosesTheConnection() throws IOException {
        server = echoApp(JoltConfig.defaults()).listen(0);
        try (var c = new RawClient(server.port())) {
            assertEquals(400, c.send("NONSENSE\r\n\r\n").read().status());
            assertTrue(c.isClosedByPeer());
        }
    }

    @Test
    void stalledRequestGets408() throws IOException {
        server = echoApp(JoltConfig.defaults().readTimeout(Duration.ofMillis(200))).listen(0);
        try (var c = new RawClient(server.port())) {
            Reply r = c.send("GET / HTTP/1.1\r\nHost: t\r\n").read();
            assertEquals(408, r.status());
        }
    }

    @Test
    void idleKeepAliveConnectionIsClosedAfterTheTimeout() throws IOException {
        server = echoApp(JoltConfig.defaults().keepAliveTimeout(Duration.ofMillis(200))).listen(0);
        try (var c = new RawClient(server.port())) {
            assertEquals("hello", c.get("/").text());
            assertTrue(c.isClosedByPeer());
        }
    }

    @Test
    void connectionIsClosedAfterMaxRequests() throws IOException {
        server = echoApp(JoltConfig.defaults().maxRequestsPerConnection(2)).listen(0);
        try (var c = new RawClient(server.port())) {
            assertEquals("keep-alive", c.get("/").header("Connection"));
            assertEquals("close", c.get("/").header("Connection"));
            assertTrue(c.isClosedByPeer());
        }
    }

    @Test
    void clientDisconnectMidBodyIsHandledQuietly() throws IOException {
        server = echoApp(JoltConfig.defaults()).listen(0);
        try (var c = new RawClient(server.port())) {
            c.send("POST /echo HTTP/1.1\r\nContent-Length: 50\r\n\r\nabc");
        }
        try (var c = new RawClient(server.port())) {
            assertEquals("hello", c.get("/").text());
        }
    }

    @Test
    void answers503WhenConnectionLimitIsReached() throws IOException {
        server = echoApp(JoltConfig.defaults().maxConnections(1)).listen(0);
        try (var first = new RawClient(server.port())) {
            assertEquals("hello", first.get("/").text());
            try (var second = new RawClient(server.port())) {
                Reply r = second.read();
                assertEquals(503, r.status());
                assertEquals("close", r.header("Connection"));
            }
        }
    }

    @Test
    void permitIsReleasedWhenAConnectionEnds() throws IOException {
        server = echoApp(JoltConfig.defaults().maxConnections(1)).listen(0);
        for (int i = 0; i < 5; i++) {
            try (var c = new RawClient(server.port())) {
                assertEquals("hello", c.send("GET / HTTP/1.0\r\n\r\n").read().text());
                assertTrue(c.isClosedByPeer());
            }
            sleep(50);
        }
    }

    @Test
    void manyConcurrentConnectionsAreServed() throws Exception {
        server = echoApp(JoltConfig.defaults()).listen(0);
        int n = 200;
        try (var exec = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            var futures = new java.util.ArrayList<java.util.concurrent.Future<String>>();
            for (int i = 0; i < n; i++) {
                futures.add(
                        exec.submit(
                                () -> {
                                    try (var c = new RawClient(server.port())) {
                                        return c.get("/").text();
                                    }
                                }));
            }
            for (var f : futures) {
                assertEquals("hello", f.get());
            }
        }
    }

    @Test
    void closeDropsIdleConnectionsAndStopsListening() throws IOException {
        server = echoApp(JoltConfig.defaults()).listen(0);
        int port = server.port();
        try (var c = new RawClient(port)) {
            assertEquals("hello", c.get("/").text());
            long t = System.nanoTime();
            server.close();
            assertTrue(Duration.ofNanos(System.nanoTime() - t).toSeconds() < 4);
            assertTrue(c.isClosedByPeer());
        }
        assertThrows(ConnectException.class, () -> new Socket("127.0.0.1", port));
    }

    @Test
    void closeWaitsForInFlightRequests() throws Exception {
        App app = Jolt.create();
        app.get(
                "/slow",
                (req, res) -> {
                    Thread.sleep(300);
                    res.text("done");
                });
        server = app.listen(0);
        try (var c = new RawClient(server.port())) {
            c.send("GET /slow HTTP/1.1\r\n\r\n");
            sleep(100);
            server.close();
            Reply r = c.read();
            assertEquals("done", r.text());
            assertEquals("close", r.header("Connection"));
        }
    }

    @Test
    void listenOnABusyPortFails() {
        server = Jolt.create().listen(0);
        assertThrows(java.io.UncheckedIOException.class, () -> Jolt.create().listen(server.port()));
    }

    @Test
    void sendFileServesFilesAndMissingFilesAre404(
            @org.junit.jupiter.api.io.TempDir java.nio.file.Path dir) throws IOException {
        java.nio.file.Path html = dir.resolve("a.html");
        java.nio.file.Files.writeString(html, "<b>hi</b>");
        java.nio.file.Path bin = dir.resolve("blob");
        java.nio.file.Files.write(bin, new byte[] {9});
        App app = Jolt.create();
        app.get("/f", (req, res) -> res.sendFile(html));
        app.get("/b", (req, res) -> res.sendFile(bin));
        app.get("/m", (req, res) -> res.sendFile(dir.resolve("missing.png")));
        app.get("/d", (req, res) -> res.sendFile(dir));
        server = app.listen(0);
        try (var c = new RawClient(server.port())) {
            Reply f = c.get("/f");
            assertEquals("<b>hi</b>", f.text());
            assertEquals("text/html; charset=utf-8", f.header("Content-Type"));
            assertEquals("application/octet-stream", c.get("/b").header("Content-Type"));
            assertEquals(404, c.get("/m").status());
            assertEquals(500, c.get("/d").status());
        }
    }

    @Test
    void sendFileTypesByExtension() throws IOException {
        for (String ext :
                List.of(
                        "css", "js", "json", "txt", "png", "jpg", "gif", "svg", "ico", "pdf",
                        "htm")) {
            assertFalse(typeOf(ext).isEmpty(), ext);
        }
        assertEquals("image/png", typeOf("png"));
    }

    private String typeOf(String ext) throws IOException {
        java.nio.file.Path f = java.nio.file.Files.createTempFile("jolt", "." + ext);
        try {
            Response r = new Response(JoltConfig.defaults().jsonMapper()).sendFile(f);
            return r.headers().stream()
                    .filter(h -> h.getKey().equals("Content-Type"))
                    .findFirst()
                    .get()
                    .getValue();
        } finally {
            java.nio.file.Files.delete(f);
        }
    }

    @Test
    void configWithersReplaceOneFieldEach() {
        JoltConfig c =
                JoltConfig.defaults()
                        .maxConnections(1)
                        .maxHeaderBytes(2)
                        .maxBodyBytes(3)
                        .maxRequestsPerConnection(4)
                        .maxRequestLineBytes(7)
                        .maxHeaderCount(8)
                        .readTimeout(Duration.ofSeconds(5))
                        .keepAliveTimeout(Duration.ofSeconds(6))
                        .jsonMapper(
                                new JsonMapper() {
                                    public byte[] write(Object v) {
                                        return "X".getBytes(StandardCharsets.UTF_8);
                                    }

                                    public <T> T read(byte[] j, Class<T> t) {
                                        return null;
                                    }
                                });
        assertEquals(1, c.maxConnections());
        assertEquals(2, c.maxHeaderBytes());
        assertEquals(3, c.maxBodyBytes());
        assertEquals(4, c.maxRequestsPerConnection());
        assertEquals(7, c.maxRequestLineBytes());
        assertEquals(8, c.maxHeaderCount());
        assertEquals(Duration.ofSeconds(5), c.readTimeout());
        assertEquals(Duration.ofSeconds(6), c.keepAliveTimeout());
        assertEquals("X", new String(c.jsonMapper().write(1), StandardCharsets.UTF_8));
        assertEquals("Unknown", HttpStatus.reasonFor(299));
        assertEquals("Not Found", HttpStatus.NOT_FOUND.reason());
        assertEquals(new HttpException(HttpStatus.NOT_FOUND, "x").status(), 404);
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
