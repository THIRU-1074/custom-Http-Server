package in.jolt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import in.jolt.RawClient.Reply;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class ExpectContinueTest {

    private Server server;
    private final AtomicInteger handled = new AtomicInteger();

    @AfterEach
    void stop() {
        if (server != null) {
            server.close();
        }
    }

    private int start(JoltConfig config) {
        App app = Jolt.create(config);
        app.post(
                "/len",
                (req, res) -> {
                    handled.incrementAndGet();
                    res.text(String.valueOf(req.body().length));
                });
        server = app.listen(0);
        return server.port();
    }

    private static String head(String version, String expect, String framing) {
        return "POST /len "
                + version
                + "\r\nHost: t\r\nExpect: "
                + expect
                + "\r\n"
                + framing
                + "\r\n\r\n";
    }

    @Test
    void interimResponseArrivesBeforeTheBodyIsSent() throws IOException {
        int port = start(JoltConfig.defaults());
        try (var c = new RawClient(port)) {
            c.send(head("HTTP/1.1", "100-continue", "Content-Length: 5"));
            Reply interim = c.read();
            assertEquals(100, interim.status());
            c.send("hello");
            Reply last = c.read();
            assertEquals(200, last.status());
            assertEquals("5", last.text());
        }
    }

    @Test
    void chunkedBodyAlsoGetsTheInterimResponse() throws IOException {
        int port = start(JoltConfig.defaults());
        try (var c = new RawClient(port)) {
            c.send(head("HTTP/1.1", "100-continue", "Transfer-Encoding: chunked"));
            assertEquals(100, c.read().status());
            c.send("3\r\nabc\r\n0\r\n\r\n");
            assertEquals("3", c.read().text());
        }
    }

    @Test
    void valueIsCaseInsensitive() throws IOException {
        int port = start(JoltConfig.defaults());
        try (var c = new RawClient(port)) {
            c.send(head("HTTP/1.1", "100-Continue", "Content-Length: 1"));
            assertEquals(100, c.read().status());
            c.send("x");
            assertEquals(200, c.read().status());
        }
    }

    @Test
    void declaredLengthOverTheLimitGets413WithoutReadingTheBody() throws IOException {
        int port = start(JoltConfig.defaults().maxBodyBytes(10));
        try (var c = new RawClient(port)) {
            c.send(head("HTTP/1.1", "100-continue", "Content-Length: 11"));
            Reply r = c.read();
            assertEquals(413, r.status());
            assertTrue(c.isClosedByPeer());
        }
        assertEquals(0, handled.get());
    }

    @Test
    void lengthAtTheLimitIsAccepted() throws IOException {
        int port = start(JoltConfig.defaults().maxBodyBytes(10));
        try (var c = new RawClient(port)) {
            c.send(head("HTTP/1.1", "100-continue", "Content-Length: 10"));
            assertEquals(100, c.read().status());
            c.send("0123456789");
            assertEquals("10", c.read().text());
        }
    }

    @Test
    void requestsThatWillBeRejectedGetTheirErrorWithoutA100() throws IOException {
        String[][] cases = {
            {"Content-Length: 1\r\nTransfer-Encoding: chunked", "400"},
            {"Transfer-Encoding: gzip", "501"},
            {"Content-Length: 5, 5", "400"},
            {"Content-Length: 5\r\nContent-Length: 6", "400"},
            {"Content-Length: abc", "400"},
            {"Content-Length: -1", "400"},
            {"Content-Length: 11", "413"},
        };
        int port = start(JoltConfig.defaults().maxBodyBytes(10));
        for (String[] c : cases) {
            try (var c1 = new RawClient(port)) {
                c1.send(head("HTTP/1.1", "100-continue", c[0]));
                assertEquals(Integer.parseInt(c[1]), c1.read().status(), c[0]);
                assertTrue(c1.isClosedByPeer(), c[0]);
            }
        }
        assertEquals(0, handled.get());
    }

    @Test
    void otherExpectationsGet417() throws IOException {
        int port = start(JoltConfig.defaults());
        try (var c = new RawClient(port)) {
            c.send(head("HTTP/1.1", "fancy-thing", "Content-Length: 1"));
            assertEquals(417, c.read().status());
        }
        assertEquals(0, handled.get());
    }

    @Test
    void http10IgnoresExpect() throws IOException {
        int port = start(JoltConfig.defaults());
        try (var c = new RawClient(port)) {
            c.send(head("HTTP/1.0", "100-continue", "Content-Length: 2"));
            c.send("hi");
            Reply r = c.read();
            assertEquals(200, r.status());
            assertEquals("2", r.text());
        }
    }

    @Test
    void expectWithoutABodyGetsNoInterimResponse() throws IOException {
        int port = start(JoltConfig.defaults());
        try (var c = new RawClient(port)) {
            c.send(head("HTTP/1.1", "100-continue", "Content-Length: 0"));
            assertEquals(200, c.read().status());
        }
    }
}
