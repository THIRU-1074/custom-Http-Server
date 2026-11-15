package in.jolt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import in.jolt.RawClient.Reply;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** Wire-level behaviour over a real loopback socket on an ephemeral port. */
class ProtocolTest {

    private Server server;

    @AfterEach
    void stop() {
        if (server != null) {
            server.close();
        }
    }

    private int start(JoltConfig config) {
        App app = Jolt.create(config);
        app.get("/n/:n", (req, res) -> res.text(req.param("n")));
        app.post("/len", (req, res) -> res.text(String.valueOf(req.body().length)));
        server = app.listen(0);
        return server.port();
    }

    @Test
    void pipelinedMixedRequestsGetOrderedResponsesOnOneConnection() throws IOException {
        int port = start(JoltConfig.defaults());
        try (var c = new RawClient(port)) {
            c.send(
                    "GET /n/1 HTTP/1.1\r\nHost: t\r\n\r\n"
                            + "POST /len HTTP/1.1\r\nHost: t\r\nContent-Length: 4\r\n\r\nabcd"
                            + "POST /len HTTP/1.1\r\nHost: t\r\nTransfer-Encoding: chunked\r\n\r\n"
                            + "3\r\nxyz\r\n2\r\nzz\r\n0\r\n\r\n"
                            + "GET /n/4 HTTP/1.1\r\nHost: t\r\n\r\n");
            assertEquals("1", c.read().text());
            assertEquals("4", c.read().text());
            assertEquals("5", c.read().text());
            assertEquals("4", c.read().text());
        }
    }

    @Test
    void requestTrickledByteByByteIsStillParsed() throws IOException {
        int port = start(JoltConfig.defaults());
        try (var c = new RawClient(port)) {
            for (char ch : "GET /n/9 HTTP/1.1\r\nHost: t\r\n\r\n".toCharArray()) {
                c.send(String.valueOf(ch));
            }
            assertEquals("9", c.read().text());
        }
    }

    @Test
    void keepAliveConnectionServesManyRequestsAndAdvertisesIt() throws IOException {
        int port = start(JoltConfig.defaults());
        try (var c = new RawClient(port)) {
            for (int i = 0; i < 20; i++) {
                Reply r = c.get("/n/" + i);
                assertEquals(String.valueOf(i), r.text());
                assertEquals("keep-alive", r.header("Connection"));
            }
        }
    }

    @Test
    void http10WithoutKeepAliveClosesAfterOneResponse() throws IOException {
        int port = start(JoltConfig.defaults());
        try (var c = new RawClient(port)) {
            Reply r = c.send("GET /n/1 HTTP/1.0\r\n\r\n").read();
            assertEquals("close", r.header("Connection"));
            assertTrue(c.isClosedByPeer());
        }
    }

    @Test
    void http10KeepAliveHeaderKeepsTheConnectionOpen() throws IOException {
        int port = start(JoltConfig.defaults());
        try (var c = new RawClient(port)) {
            Reply r = c.send("GET /n/1 HTTP/1.0\r\nConnection: keep-alive\r\n\r\n").read();
            assertEquals("keep-alive", r.header("Connection"));
            assertEquals("2", c.send("GET /n/2 HTTP/1.0\r\n\r\n").read().text());
            assertTrue(c.isClosedByPeer());
        }
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(
            delimiter = '|',
            value = {
                "GET /n/1~~|400",
                "GET /n/1 HTTP/1.1 x~~|400",
                "BREW /n/1 HTTP/1.1~~|501",
                "GET /n/1 HTTP/3~~|505",
                "GET /n/%zz HTTP/1.1~~|400",
                "POST /len HTTP/1.1~Content-Length: abc~~|400",
                "POST /len HTTP/1.1~Transfer-Encoding: chunked~~zz~|400"
            })
    void malformedRequestsAreAnsweredThenClosed(String raw, int status) throws IOException {
        int port = start(JoltConfig.defaults());
        try (var c = new RawClient(port)) {
            Reply r = c.send(raw.replace("~", "\r\n")).read();
            assertEquals(status, r.status());
            assertEquals("close", r.header("Connection"));
            assertTrue(c.isClosedByPeer());
        }
    }

    @Test
    void oversizeHeadersAndBodiesAreRejectedOnTheWire() throws IOException {
        int port = start(JoltConfig.defaults().maxHeaderBytes(128).maxBodyBytes(16));
        try (var c = new RawClient(port)) {
            c.send("GET /n/1 HTTP/1.1\r\nX: " + "a".repeat(200) + "\r\n\r\n");
            assertEquals(431, c.read().status());
        }
        try (var c = new RawClient(port)) {
            c.send("POST /len HTTP/1.1\r\nContent-Length: 17\r\n\r\n");
            assertEquals(413, c.read().status());
        }
        try (var c = new RawClient(port)) {
            c.send(
                    "POST /len HTTP/1.1\r\nTransfer-Encoding: chunked\r\n\r\n8\r\n12345678\r\n9\r\n");
            assertEquals(413, c.read().status());
        }
    }

    @Test
    void saturationAnswers503ToEveryExtraConnectionAndRecoversWhenSlotsFree() throws IOException {
        int port = start(JoltConfig.defaults().maxConnections(3));
        List<RawClient> held = new ArrayList<>();
        try {
            for (int i = 0; i < 3; i++) {
                var c = new RawClient(port);
                assertEquals("ok", okBody(c));
                held.add(c);
            }
            for (int i = 0; i < 3; i++) {
                try (var extra = new RawClient(port)) {
                    Reply r = extra.read();
                    assertEquals(503, r.status());
                    assertEquals("close", r.header("Connection"));
                    assertEquals("Server busy", r.text());
                }
            }
            held.remove(0).close();
            assertEquals("1", eventuallyServed(port));
        } finally {
            for (var c : held) {
                c.close();
            }
        }
    }

    private static String okBody(RawClient c) throws IOException {
        Reply r = c.get("/n/1");
        return r.status() == 200 ? "ok" : "status " + r.status();
    }

    private static String eventuallyServed(int port) throws IOException {
        for (int i = 0; i < 50; i++) {
            try (var c = new RawClient(port)) {
                Reply r = c.get("/n/1");
                if (r.status() == 200) {
                    return r.text();
                }
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        throw new AssertionError("slot was never released");
    }
}
