package in.jolt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import in.jolt.RawClient.Reply;
import java.io.IOException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(30)
class ErrorHeadersTest {

    private Server server;

    @AfterEach
    void stop() {
        if (server != null) {
            server.close();
        }
    }

    private Reply get(String path) throws IOException {
        App app = Jolt.create();
        app.use(Cors.allow("https://a.io"));
        app.use(
                (req, res, next) -> {
                    res.header("X-Content-Type-Options", "nosniff");
                    next.run();
                });
        app.get(
                "/forbidden",
                (req, res) -> {
                    res.header("Content-Type", "application/json")
                            .header("Content-Length", "999")
                            .header("Transfer-Encoding", "chunked")
                            .cookie("sid", "abc");
                    throw new HttpException(403, "nope");
                });
        app.get(
                "/boom",
                (req, res) -> {
                    res.cookie("sid", "abc");
                    throw new IllegalStateException("intentional test failure");
                });
        server = app.listen(0);
        try (var c = new RawClient(server.port())) {
            return c.send("GET " + path + " HTTP/1.1\r\nOrigin: https://a.io\r\n\r\n").read();
        }
    }

    @Test
    void httpExceptionKeepsMiddlewareHeadersAndDropsBodyFraming() throws IOException {
        Reply r = get("/forbidden");
        assertEquals(403, r.status());
        assertEquals("nope", r.text());
        assertEquals("https://a.io", r.header("Access-Control-Allow-Origin"));
        assertEquals("Origin", r.header("Vary"));
        assertEquals("nosniff", r.header("X-Content-Type-Options"));
        assertEquals("text/plain; charset=utf-8", r.header("Content-Type"));
        assertEquals("4", r.header("Content-Length"));
        assertNull(r.header("Set-Cookie"));
        assertNull(r.header("Transfer-Encoding"));
    }

    @Test
    void unexpectedExceptionKeepsMiddlewareHeadersToo() throws IOException {
        Reply r = get("/boom");
        assertEquals(500, r.status());
        assertEquals("https://a.io", r.header("Access-Control-Allow-Origin"));
        assertEquals("nosniff", r.header("X-Content-Type-Options"));
        assertNull(r.header("Set-Cookie"));
    }
}
