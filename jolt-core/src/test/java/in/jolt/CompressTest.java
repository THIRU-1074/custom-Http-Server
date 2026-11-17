package in.jolt;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import in.jolt.test.JoltTest;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;
import java.util.zip.GZIPInputStream;
import org.junit.jupiter.api.Test;

class CompressTest {

    private static Path files() {
        try {
            Path dir = Files.createTempDirectory("jolt-compress");
            Files.writeString(dir.resolve("big.txt"), "hello jolt ".repeat(300));
            dir.toFile().deleteOnExit();
            dir.resolve("big.txt").toFile().deleteOnExit();
            return dir;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static final String BIG = "hello jolt ".repeat(300);
    private static final Path FILES = files();
    private static final byte[] BIG_BYTES = BIG.getBytes(StandardCharsets.UTF_8);

    private static JoltTest client(Middleware mw) {
        App app = Jolt.create();
        app.use(mw);
        app.get("/big", (req, res) -> res.text(BIG));
        app.get("/small", (req, res) -> res.text("tiny"));
        app.get("/png", (req, res) -> res.header("Content-Type", "image/png").send(BIG_BYTES));
        app.get("/svg", (req, res) -> res.header("Content-Type", "image/svg+xml").send(BIG_BYTES));
        app.get("/enc", (req, res) -> res.header("Content-Encoding", "br").text(BIG));
        app.get(
                "/noise",
                (req, res) -> {
                    byte[] b = new byte[4000];
                    new Random(1).nextBytes(b);
                    res.send(b);
                });
        app.get(
                "/v",
                (req, res) -> res.header("Vary", "Origin").header("ETag", "\"abc\"").text(BIG));
        app.get("/r", (req, res) -> res.redirect("/x"));
        app.get("/nt", (req, res) -> res.header("Cache-Control", "no-transform").text(BIG));
        app.get("/ar", (req, res) -> res.header("Accept-Ranges", "bytes").text(BIG));
        app.get("/empty", (req, res) -> res.send(new byte[0]));
        app.use("/f", Static.serve("/f", FILES));
        return JoltTest.of(app);
    }

    private static String encoding(JoltTest t, String path, String acceptEncoding) {
        var call = t.get(path);
        if (acceptEncoding != null) {
            call.header("Accept-Encoding", acceptEncoding);
        }
        return call.expect(200).header("Content-Encoding");
    }

    @Test
    void compressedBodyDecodesToTheOriginal() throws IOException {
        var r =
                client(Compress.gzip())
                        .get("/big")
                        .header("Accept-Encoding", "gzip, deflate")
                        .expect(200);
        assertEquals("gzip", r.header("Content-Encoding"));
        assertEquals("Accept-Encoding", r.header("Vary"));
        assertTrue(r.bytes().length < BIG_BYTES.length);
        assertArrayEquals(
                BIG_BYTES, new GZIPInputStream(new ByteArrayInputStream(r.bytes())).readAllBytes());
        assertEquals("text/plain; charset=utf-8", r.header("Content-Type"));
    }

    @Test
    void smallBodiesAreSkippedButStillVary() {
        var r = client(Compress.gzip()).get("/small").header("Accept-Encoding", "gzip").expect(200);
        assertNull(r.header("Content-Encoding"));
        assertEquals("Accept-Encoding", r.header("Vary"));
        assertEquals("tiny", r.text());
    }

    @Test
    void minimumSizeIsConfigurable() {
        assertNull(encoding(client(Compress.gzip(10_000)), "/big", "gzip"));
        assertEquals("gzip", encoding(client(Compress.gzip(100)), "/big", "gzip"));
    }

    @Test
    void clientsWithoutGzipGetPlainBodyButStillVary() {
        var t = client(Compress.gzip());
        var none = t.get("/big").expect(200);
        assertNull(none.header("Content-Encoding"));
        assertEquals("Accept-Encoding", none.header("Vary"));
        assertEquals(BIG, none.text());
        assertNull(encoding(t, "/big", "br"));
    }

    @Test
    void qZeroIsRespectedAndWildcardAccepted() {
        var t = client(Compress.gzip());
        assertNull(encoding(t, "/big", "gzip;q=0"));
        assertNull(encoding(t, "/big", "*;q=0, br"));
        assertNull(encoding(t, "/big", "gzip;q=0, *"));
        assertEquals("gzip", encoding(t, "/big", "*"));
        assertEquals("gzip", encoding(t, "/big", "GZIP;q=0.5"));
    }

    @Test
    void alreadyCompressedTypesAndEncodedResponsesAreLeftAlone() {
        var t = client(Compress.gzip());
        assertNull(encoding(t, "/png", "gzip"));
        assertEquals("br", encoding(t, "/enc", "gzip"));
        assertEquals("gzip", encoding(t, "/svg", "gzip"));
    }

    @Test
    void incompressibleBodiesAreSentAsIs() {
        var r = client(Compress.gzip()).get("/noise").header("Accept-Encoding", "gzip").expect(200);
        assertNull(r.header("Content-Encoding"));
        assertEquals(4000, r.bytes().length);
    }

    @Test
    void existingVaryIsExtendedAndStrongEtagBecomesWeak() {
        var r = client(Compress.gzip()).get("/v").header("Accept-Encoding", "gzip").expect(200);
        assertEquals("Origin, Accept-Encoding", r.header("Vary"));
        assertEquals("W/\"abc\"", r.header("ETag"));
    }

    @Test
    void redirectsPassThrough() {
        var r = client(Compress.gzip(1)).get("/r").header("Accept-Encoding", "gzip").expect(302);
        assertNull(r.header("Content-Encoding"));
        assertEquals(0, r.bytes().length);
    }

    @Test
    void noTransformRangedAndPartialResponsesStayIdentity() {
        var t = client(Compress.gzip());
        assertNull(encoding(t, "/nt", "gzip"));
        assertNull(encoding(t, "/ar", "gzip"));
        var file = t.get("/f/big.txt").header("Accept-Encoding", "gzip").expect(200);
        assertNull(file.header("Content-Encoding"));
        assertEquals(BIG, file.text());
        var part =
                t.get("/f/big.txt")
                        .header("Accept-Encoding", "gzip")
                        .header("Range", "bytes=0-9")
                        .expect(206);
        assertNull(part.header("Content-Encoding"));
        assertEquals(10, part.bytes().length);
    }

    @Test
    void headAndEmptyResponsesAreNotCompressedButVary() {
        var t = client(Compress.gzip());
        var head = t.head("/big").header("Accept-Encoding", "gzip").expect(200);
        assertNull(head.header("Content-Encoding"));
        assertEquals("Accept-Encoding", head.header("Vary"));
        var empty = t.get("/empty").header("Accept-Encoding", "gzip").expect(200);
        assertNull(empty.header("Content-Encoding"));
    }
}
