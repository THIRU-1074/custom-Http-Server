package in.jolt.internal.http;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import in.jolt.HttpException;
import in.jolt.JoltConfig;
import in.jolt.internal.http.HttpParser.ParsedRequest;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** Table-driven edge cases; {@code ~} stands for CRLF in the tables. */
class HttpParserEdgeTest {

    private static final Limits LIMITS =
            Limits.from(JoltConfig.defaults().maxHeaderBytes(256).maxBodyBytes(64));

    private static ParsedRequest parse(String raw) throws IOException {
        var in =
                new ByteArrayInputStream(
                        raw.replace("~", "\r\n").getBytes(StandardCharsets.ISO_8859_1));
        return HttpParser.parse(in, LIMITS);
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource(
            delimiter = '|',
            value = {
                "GET /~~|400",
                "GET  / HTTP/1.1~~|400",
                "GET / HTTP/1.1 x~~|400",
                "GET / HTTP/1.1~no-colon~~|400",
                "GET / HTTP/1.1~: empty-name~~|400",
                "GET x HTTP/1.1~~|400",
                "GET /%zz HTTP/1.1~~|400",
                "GET /%4 HTTP/1.1~~|400",
                "GET /% HTTP/1.1~~|400",
                "get / HTTP/1.1~~|501",
                "TRACE / HTTP/1.1~~|501",
                "GET / HTTP/2.0~~|505",
                "GET / HTTP/1.2~~|505",
                "POST / HTTP/1.1~Content-Length: 1x~~|400",
                "POST / HTTP/1.1~Content-Length: 99999999999999999999~~|400",
                "POST / HTTP/1.1~Content-Length: 65~~|413",
                "POST / HTTP/1.1~Transfer-Encoding: chunked~~4~abcd~3D~|413",
                "POST / HTTP/1.1~Transfer-Encoding: chunked~~ffffffffff~|400",
                "POST / HTTP/1.1~Transfer-Encoding: chunked~~~|400"
            })
    void rejectsWithStatus(String raw, int status) {
        var e = assertThrows(HttpException.class, () -> parse(raw));
        assertEquals(status, e.status());
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource(
            delimiter = '|',
            value = {
                "/plain|/plain",
                "/a%20b|/a b",
                "/a+b|/a+b",
                "/a%2Fb|/a/b",
                "/%e2%82%ac|/€",
                "/%C3%A9%C3%A8|/éè",
                "/%41%42|/AB",
                "/a%00b|/a\u0000b"
            })
    void decodesPathButNotPlus(String target, String expected) throws IOException {
        assertEquals(expected, parse("GET " + target + " HTTP/1.1~~").path());
    }

    @Test
    void queryStaysRawAndFragmentFreeQuestionMarksSplitOnce() throws IOException {
        ParsedRequest r = parse("GET /p?a=%20&b=c?d HTTP/1.1~~");
        assertEquals("/p", r.path());
        assertEquals("a=%20&b=c?d", r.rawQuery());
    }

    @Test
    void headerNamesAreCaseInsensitiveAndValuesTrimmed() throws IOException {
        ParsedRequest r = parse("GET / HTTP/1.1~hOsT:   spaced  ~X-A: 1~x-a: 2~~");
        assertEquals("spaced", r.headers().get("HOST"));
        assertEquals("1, 2", r.headers().get("X-A"));
        assertEquals("1, 2", r.headers().get("x-a"));
    }

    @Test
    void leadingBlankLinesAreSkipped() throws IOException {
        assertEquals("/x", parse("~~GET /x HTTP/1.1~~").path());
    }

    @Test
    void headerBlockAtExactlyTheLimitPassesAndOneOverIs431() throws IOException {
        String line = "GET / HTTP/1.1\r\nX: ";
        int pad = 256 - line.length() - 4;
        assertEquals("a".repeat(pad), parse(line + "a".repeat(pad) + "~~").headers().get("X"));
        var e = assertThrows(HttpException.class, () -> parse(line + "a".repeat(pad + 1) + "~~"));
        assertEquals(431, e.status());
    }

    @Test
    void bodyAtExactlyTheLimitPasses() throws IOException {
        ParsedRequest r = parse("POST / HTTP/1.1~Content-Length: 64~~" + "x".repeat(64));
        assertEquals(64, r.body().length);
    }

    @Test
    void whitespaceAroundTheChunkSizeIsRejected() {
        for (String size : new String[] {"3 ", " 3"}) {
            var e =
                    assertThrows(
                            HttpException.class,
                            () ->
                                    parse(
                                            "POST / HTTP/1.1~Transfer-Encoding: chunked~~"
                                                    + size
                                                    + "~abc~0~~"));
            assertEquals(400, e.status());
        }
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(
            delimiter = '|',
            value = {
                "3~abc~0~~|abc",
                "3~abc~2~de~0~~|abcde",
                "A~0123456789~0~~|0123456789",
                "a~0123456789~0~~|0123456789",
                "3;ext=1~abc~0~~|abc",
                "0~~|",
                "3~abc~0~X-Trailer: t~~|abc"
            })
    void decodesChunkedBodies(String chunks, String expected) throws IOException {
        ParsedRequest r = parse("POST / HTTP/1.1~Transfer-Encoding: chunked~~" + chunks);
        assertArrayEquals(
                expected == null ? new byte[0] : expected.getBytes(StandardCharsets.UTF_8),
                r.body());
    }

    @Test
    void chunkedWithContentLengthIsRejected() {
        assertThrows(
                HttpException.class,
                () ->
                        parse(
                                "POST / HTTP/1.1~Transfer-Encoding: Chunked~Content-Length: 2~~3~abc~0~~"));
    }

    @Test
    void chunkedBinaryBytesSurviveIntact() throws IOException {
        byte[] raw = {0, 13, 10, (byte) 0xFF};
        var out = new java.io.ByteArrayOutputStream();
        out.writeBytes("POST / HTTP/1.1\r\nTransfer-Encoding: chunked\r\n\r\n4\r\n".getBytes());
        out.writeBytes(raw);
        out.writeBytes("\r\n0\r\n\r\n".getBytes());
        assertArrayEquals(
                raw, HttpParser.parse(new ByteArrayInputStream(out.toByteArray()), LIMITS).body());
    }
}
