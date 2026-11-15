package in.jolt.internal.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import in.jolt.HttpException;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class HttpParserStrictTest {

    private static final Limits LIMITS = new Limits(1024, 64, 64, 5);

    private static HttpParser.ParsedRequest parse(String s, Limits limits) throws IOException {
        return HttpParser.parse(
                new ByteArrayInputStream(s.getBytes(StandardCharsets.ISO_8859_1)), limits);
    }

    private static int statusOf(String s) {
        return assertThrows(HttpException.class, () -> parse(s, LIMITS)).status();
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "GET / HTTP/1.1\nHost: h\r\n\r\n",
                "GET / HTTP/1.1\r\nHost: h\nX: 1\r\n\r\n",
                "GET /\rx HTTP/1.1\r\nHost: h\r\n\r\n",
                "GET / HTTP/1.1\r\nHost: h\rX: 1\r\n\r\n",
                "GET / HTTP/1.1\r\nX: a\u0000b\r\n\r\n",
                "GET / HTTP/1.1\r\nX: a\u0001b\r\n\r\n",
                "GET / HTTP/1.1\r\nX: a\u007fb\r\n\r\n",
            })
    void rejectsBareLineBreaksAndControlCharacters(String raw) {
        assertEquals(400, statusOf(raw));
    }

    @Test
    void horizontalTabInsideAValueIsAllowed() throws IOException {
        assertEquals("a\tb", parse("GET / HTTP/1.1\r\nX: a\tb\r\n\r\n", LIMITS).headers().get("X"));
    }

    @Test
    void skipsAtMostEightLeadingLineBreaks() throws IOException {
        assertEquals("/", parse("\r\n".repeat(4) + "GET / HTTP/1.1\r\n\r\n", LIMITS).path());
        assertEquals(400, statusOf("\r\n".repeat(5) + "GET / HTTP/1.1\r\n\r\n"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"+5", "-1", "0x5", " 5", "5 ", "", "g", "5_0"})
    void rejectsNonHexChunkSizes(String size) {
        String raw =
                "POST / HTTP/1.1\r\nTransfer-Encoding: chunked\r\n\r\n"
                        + size
                        + "\r\nhello\r\n0\r\n\r\n";
        assertEquals(400, statusOf(raw));
    }

    @Test
    void rejectsBareLfChunkTerminators() {
        String head = "POST / HTTP/1.1\r\nTransfer-Encoding: chunked\r\n\r\n";
        assertEquals(400, statusOf(head + "5\nhello\r\n0\r\n\r\n"));
        assertEquals(400, statusOf(head + "5\r\nhello\n0\r\n\r\n"));
        assertEquals(400, statusOf(head + "5\r\nhello\r\n0\r\n\n"));
    }

    @Test
    void acceptsHexChunkSizesWithExtensions() throws IOException {
        var r =
                parse(
                        "POST / HTTP/1.1\r\nTransfer-Encoding: chunked\r\n\r\n"
                                + "A;ext=1\r\n0123456789\r\n0\r\n\r\n",
                        LIMITS);
        assertEquals(10, r.body().length);
    }
}
