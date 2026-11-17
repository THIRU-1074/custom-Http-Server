package in.jolt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import in.jolt.test.JoltTest;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;

class NegotiationTest {

    static final class KvCodec implements BodyCodec {
        @Override
        public String mediaType() {
            return "text/x-kv";
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> T read(byte[] bytes, Class<T> type) {
            if (type != Map.class) {
                throw new IllegalArgumentException("Only Map is supported");
            }
            Map<String, String> out = new TreeMap<>();
            for (String line : new String(bytes, StandardCharsets.UTF_8).split("\n")) {
                int eq = line.indexOf('=');
                if (eq < 0) {
                    throw new IllegalArgumentException("Line without '='");
                }
                out.put(line.substring(0, eq), line.substring(eq + 1));
            }
            return (T) out;
        }

        @Override
        public byte[] write(Object value) {
            StringBuilder sb = new StringBuilder();
            new TreeMap<>((Map<?, ?>) value)
                    .forEach((k, v) -> sb.append(k).append('=').append(v).append('\n'));
            return sb.toString().getBytes(StandardCharsets.UTF_8);
        }
    }

    private static JoltTest client(boolean withCodec) {
        App app = Jolt.create();
        if (withCodec) {
            app.codec(new KvCodec());
        }
        app.post("/echo", (req, res) -> res.send(req.body(Map.class)));
        app.get("/data", (req, res) -> res.send(Map.of("a", 1)));
        app.get("/raw", (req, res) -> res.send((Object) new byte[] {1, 2}));
        return JoltTest.of(app);
    }

    @Test
    void customCodecReadsRequestsByContentType() {
        client(true)
                .post("/echo")
                .header("Content-Type", "text/x-kv; charset=utf-8")
                .body("a=1\nb=2".getBytes(StandardCharsets.UTF_8))
                .expect(200)
                .header("Content-Type", "application/json; charset=utf-8")
                .bodyContains("\"b\"");
    }

    @Test
    void jsonStaysTheDefaultWhenCodecsAreRegistered() {
        var t = client(true);
        t.post("/echo").json(Map.of("a", 1)).expect(200).bodyContains("\"a\"");
        t.post("/echo")
                .body("{\"a\":1}".getBytes(StandardCharsets.UTF_8))
                .expect(200)
                .bodyContains("\"a\"");
    }

    @Test
    void unsupportedRequestMediaTypeIs415() {
        client(true)
                .post("/echo")
                .header("Content-Type", "application/xml")
                .body("<a/>".getBytes(StandardCharsets.UTF_8))
                .expect(415);
    }

    @Test
    void withoutCodecsRequestParsingIsUnchanged() {
        client(false)
                .post("/echo")
                .header("Content-Type", "application/xml")
                .body("{\"a\":1}".getBytes(StandardCharsets.UTF_8))
                .expect(200);
    }

    @Test
    void codecRejectionIs400() {
        client(true)
                .post("/echo")
                .header("Content-Type", "text/x-kv")
                .body("nonsense".getBytes(StandardCharsets.UTF_8))
                .expect(400)
                .body("Line without '='");
    }

    @Test
    void acceptPicksTheRepresentation() {
        var t = client(true);
        var kv = t.get("/data").header("Accept", "text/x-kv").expect(200);
        kv.header("Content-Type", "text/x-kv").header("Vary", "Accept").body("a=1\n");
        t.get("/data").expect(200).header("Content-Type", "application/json; charset=utf-8");
        t.get("/data").header("Accept", "*/*").expect(200).body("{\"a\":1}");
        t.get("/data").header("Accept", "text/*").expect(200).body("a=1\n");
        t.get("/data")
                .header("Accept", "text/x-kv;q=0.9, application/json;q=0.5")
                .expect(200)
                .body("a=1\n");
        t.get("/data")
                .header("Accept", "text/x-kv;q=0.2, application/json")
                .expect(200)
                .body("{\"a\":1}");
        t.get("/data").header("Accept", "application/json;q=0, */*").expect(200).body("a=1\n");
    }

    @Test
    void unsatisfiableAcceptIs406() {
        client(true).get("/data").header("Accept", "text/html").expect(406);
        client(true)
                .get("/data")
                .header("Accept", "text/x-kv;q=0, application/json;q=0")
                .expect(406);
        var plain = client(false);
        plain.get("/data").header("Accept", "application/xml").expect(406);
        var ok = plain.get("/data").header("Accept", "application/json").expect(200);
        assertNull(ok.header("Vary"));
    }

    @Test
    void bytesPassThroughSendUntouched() {
        var r = client(true).get("/raw").header("Accept", "text/x-kv").expect(200);
        assertEquals(2, r.bytes().length);
        r.header("Content-Type", "application/octet-stream");
    }
}
