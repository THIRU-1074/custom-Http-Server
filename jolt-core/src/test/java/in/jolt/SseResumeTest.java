package in.jolt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;

class SseResumeTest {

    private static String fetch(Server server, String extraHeaders) throws IOException {
        try (Socket s = new Socket("127.0.0.1", server.port())) {
            s.setSoTimeout(5000);
            s.getOutputStream()
                    .write(
                            ("GET /s HTTP/1.1\r\nHost: t\r\n" + extraHeaders + "\r\n")
                                    .getBytes(StandardCharsets.ISO_8859_1));
            String raw = new String(s.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            return unchunk(raw.substring(raw.indexOf("\r\n\r\n") + 4));
        }
    }

    private static String unchunk(String raw) {
        StringBuilder out = new StringBuilder();
        int i = 0;
        while (true) {
            int eol = raw.indexOf("\r\n", i);
            int size = Integer.parseInt(raw.substring(i, eol), 16);
            if (size == 0) {
                return out.toString();
            }
            out.append(raw, eol + 2, eol + 2 + size);
            i = eol + 2 + size + 2;
        }
    }

    @Test
    void writesIdLinesAndReadsTheReconnectHeader() throws Exception {
        List<String> seen = new CopyOnWriteArrayList<>();
        App app = Jolt.create();
        app.get(
                "/s",
                (req, res) -> {
                    seen.add(req.lastEventId());
                    EventStream s = res.sse();
                    s.retry(Duration.ofSeconds(2));
                    s.event("7", "seat", "a\nb");
                    s.event(null, null, "plain");
                });
        try (Server server = app.listen(0)) {
            String body = fetch(server, "");
            assertEquals(
                    "retry: 2000\n\nid: 7\nevent: seat\ndata: a\ndata: b\n\ndata: plain\n\n", body);
            fetch(server, "Last-Event-ID: 7\r\n");
        }
        assertNull(seen.get(0));
        assertEquals("7", seen.get(1));
    }

    @Test
    void rejectsIdsWithLineBreaksOrNul() {
        EventStream s = new EventStream(null);
        for (String bad : new String[] {"a\nb", "a\rb", "a\0b"}) {
            assertThrows(IllegalArgumentException.class, () -> s.event(bad, "n", "d"));
        }
    }

    @Test
    void joltTestExposesIdsAndSendsLastEventId() {
        App app = Jolt.create();
        app.get(
                "/s",
                (req, res) -> {
                    EventStream s = res.sse();
                    s.event(req.lastEventId() == null ? "1" : "2", "seat", "A1");
                    s.close();
                });
        var t = in.jolt.test.JoltTest.of(app);
        assertEquals("1", t.get("/s").send().events().get(0).id());
        var r = t.get("/s").header("Last-Event-ID", "1").send();
        assertEquals(new in.jolt.test.JoltTest.Event("seat", "A1", "2"), r.events().get(0));
    }
}
