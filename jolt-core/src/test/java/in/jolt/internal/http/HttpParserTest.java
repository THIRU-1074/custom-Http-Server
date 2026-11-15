package in.jolt.internal.http;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import in.jolt.HttpException;
import in.jolt.JoltConfig;
import in.jolt.internal.http.HttpParser.ParsedRequest;
import java.io.ByteArrayInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class HttpParserTest {

    private static final Limits LIMITS = limits(1024, 64);

    private static Limits limits(int headerBytes, int bodyBytes) {
        return Limits.from(
                JoltConfig.defaults().maxHeaderBytes(headerBytes).maxBodyBytes(bodyBytes));
    }

    private static InputStream stream(String s) {
        return new ByteArrayInputStream(s.getBytes(StandardCharsets.ISO_8859_1));
    }

    private static ParsedRequest parse(String s) throws IOException {
        return HttpParser.parse(stream(s), LIMITS);
    }

    @Test
    void parsesRequestLineQueryAndCaseInsensitiveHeaders() throws IOException {
        ParsedRequest r =
                parse("GET /a/b?x=1&y=2 HTTP/1.1\r\nHost: h\r\ncontent-TYPE: text/plain\r\n\r\n");
        assertEquals("GET", r.method());
        assertEquals("/a/b", r.path());
        assertEquals("x=1&y=2", r.rawQuery());
        assertEquals("text/plain", r.headers().get("Content-Type"));
        assertEquals("h", r.headers().get("HOST"));
        assertEquals(0, r.body().length);
    }

    @Test
    void decodesPathAsUtf8() throws IOException {
        assertEquals("/café x", parse("GET /caf%C3%A9%20x HTTP/1.1\r\n\r\n").path());
    }

    @Test
    void readsExactlyContentLengthBytesAndLeavesTheRest() throws IOException {
        InputStream in =
                stream(
                        "POST /p HTTP/1.1\r\nContent-Length: 5\r\n\r\nhelloGET /next HTTP/1.1\r\n\r\n");
        ParsedRequest first = HttpParser.parse(in, LIMITS);
        assertArrayEquals("hello".getBytes(StandardCharsets.UTF_8), first.body());
        assertEquals("/next", HttpParser.parse(in, LIMITS).path());
        assertNull(HttpParser.parse(in, LIMITS));
    }

    @Test
    void keepsBinaryBodiesIntact() throws IOException {
        byte[] head =
                "POST /p HTTP/1.1\r\nContent-Length: 4\r\n\r\n".getBytes(StandardCharsets.UTF_8);
        byte[] payload = {0, (byte) 0xFF, '\r', '\n'};
        byte[] all = new byte[head.length + 4];
        System.arraycopy(head, 0, all, 0, head.length);
        System.arraycopy(payload, 0, all, head.length, 4);
        assertArrayEquals(payload, HttpParser.parse(new ByteArrayInputStream(all), LIMITS).body());
    }

    @Test
    void decodesChunkedBodies() throws IOException {
        ParsedRequest r =
                parse(
                        "POST /c HTTP/1.1\r\nTransfer-Encoding: chunked\r\n\r\n"
                                + "5;ext=1\r\nhello\r\n6\r\n world\r\n0\r\nTrailer: x\r\n\r\n");
        assertEquals("hello world", new String(r.body(), StandardCharsets.UTF_8));
    }

    @ParameterizedTest
    @CsvSource(
            delimiter = '|',
            value = {
                "GET / HTTP/1.1 extra~~|400",
                "GET /~~|400",
                "GET x HTTP/1.1~~|400",
                "GET / HTTP/2.0~~|505",
                "BREW / HTTP/1.1~~|501",
                "GET /%zz HTTP/1.1~~|400",
                "GET /% HTTP/1.1~~|400",
                "GET / HTTP/1.1~NoColon~~|400",
                "GET / HTTP/1.1~: empty~~|400",
                "POST / HTTP/1.1~Content-Length: abc~~|400",
                "POST / HTTP/1.1~Content-Length: -1~~|400",
                "POST / HTTP/1.1~Content-Length: +1~~|400",
                "POST / HTTP/1.1~Content-Length: 65~~|413",
                "GET / HTTP/1.1~Host : h~~|400",
                "GET / HTTP/1.1~Host	: h~~|400",
                "GET / HTTP/1.1~X: a~ folded~~|400",
                "GET / HTTP/1.1~ X: a~~|400",
                "POST / HTTP/1.1~Content-Length: 1~Transfer-Encoding: chunked~~0~~|400",
                "POST / HTTP/1.1~Transfer-Encoding: chunked~Content-Length: 1~~0~~|400",
                "POST / HTTP/1.1~Content-Length: 5~Content-Length: 5~~hello|400",
                "POST / HTTP/1.1~Content-Length: 5~content-length: 6~~hello|400",
                "POST / HTTP/1.1~Transfer-Encoding: gzip~~|501",
                "POST / HTTP/1.1~Transfer-Encoding: chunked~~zz~|400",
                "POST / HTTP/1.1~Transfer-Encoding: chunked~~-1~|400",
                "POST / HTTP/1.1~Transfer-Encoding: chunked~~41~|413",
                "POST / HTTP/1.1~Transfer-Encoding: chunked~~1~abX~|400",
                "GET / HTTP/1.1~|400"
            })
    void rejectsBadRequests(String raw, int status) {
        var e = assertThrows(HttpException.class, () -> parse(raw.replace("~", "\r\n")));
        assertEquals(status, e.status());
    }

    @Test
    void capsHeaderSize() {
        String big = "GET / HTTP/1.1\r\nX: " + "a".repeat(2000) + "\r\n\r\n";
        assertEquals(431, assertThrows(HttpException.class, () -> parse(big)).status());
    }

    @Test
    void capsChunkLines() {
        String raw = "POST / HTTP/1.1\r\nTransfer-Encoding: chunked\r\n\r\n" + "1".repeat(2000);
        assertEquals(400, assertThrows(HttpException.class, () -> parse(raw)).status());
    }

    @Test
    void declaredLengthDoesNotPreallocateTheBody() {
        Limits huge = limits(1024, Integer.MAX_VALUE);
        InputStream in = stream("POST / HTTP/1.1\r\nContent-Length: 2000000000\r\n\r\nabc");
        assertThrows(EOFException.class, () -> HttpParser.parse(in, huge));
    }

    @Test
    void largeBodiesAreReadInFull() throws IOException {
        byte[] head =
                "POST / HTTP/1.1\r\nContent-Length: 200000\r\n\r\n"
                        .getBytes(StandardCharsets.UTF_8);
        byte[] all = new byte[head.length + 200_000];
        System.arraycopy(head, 0, all, 0, head.length);
        java.util.Arrays.fill(all, head.length, all.length, (byte) 'x');
        ParsedRequest r = HttpParser.parse(new ByteArrayInputStream(all), limits(1024, 1_000_000));
        assertEquals(200_000, r.body().length);
    }

    @Test
    void truncatedBodyIsAnIoError() {
        assertThrows(
                EOFException.class, () -> parse("POST / HTTP/1.1\r\nContent-Length: 10\r\n\r\nab"));
        assertThrows(
                EOFException.class,
                () -> parse("POST / HTTP/1.1\r\nTransfer-Encoding: chunked\r\n\r\n5\r\nab"));
    }

    @Test
    void emptyStreamIsCleanEof() throws IOException {
        assertNull(parse(""));
        assertNull(parse("\r\n\r\n"));
    }

    @Test
    void mergesDuplicateHeaders() throws IOException {
        assertEquals("a, b", parse("GET / HTTP/1.1\r\nX: a\r\nx: b\r\n\r\n").headers().get("X"));
    }

    @Test
    void duplicateCookieHeadersAreJoinedWithSemicolons() throws IOException {
        ParsedRequest r = parse("GET / HTTP/1.1\r\nCookie: a=1\r\ncookie: b=2\r\n\r\n");
        assertEquals("a=1; b=2", r.headers().get("Cookie"));
    }

    @Test
    void keepAliveSemantics() throws IOException {
        assertTrue(parse("GET / HTTP/1.1\r\n\r\n").keepAlive());
        assertFalse(parse("GET / HTTP/1.1\r\nConnection: close\r\n\r\n").keepAlive());
        assertFalse(parse("GET / HTTP/1.0\r\n\r\n").keepAlive());
        assertTrue(parse("GET / HTTP/1.0\r\nConnection: Keep-Alive\r\n\r\n").keepAlive());
    }

    @Test
    void percentDecodeHandlesPlusAndMultibyte() {
        assertEquals("a b", Codec.percentDecode("a+b", true));
        assertEquals("a+b", Codec.percentDecode("a+b", false));
        assertEquals("😀", Codec.percentDecode("%F0%9F%98%80", false));
        assertEquals("😀", Codec.percentDecode("😀", false));
        assertThrows(IllegalArgumentException.class, () -> Codec.percentDecode("%4", false));
    }
}
