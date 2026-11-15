package in.jolt.internal.http;

import in.jolt.HttpException;
import in.jolt.HttpStatus;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/** Reads one HTTP/1.x request from a stream: header block, then exactly the declared body. */
public final class HttpParser {

    public record ParsedRequest(
            String method,
            String path,
            String rawQuery,
            String version,
            Map<String, String> headers,
            byte[] body) {

        public boolean keepAlive() {
            String conn = headers.getOrDefault("Connection", "").toLowerCase();
            return version.equals("HTTP/1.1")
                    ? !conn.contains("close")
                    : conn.contains("keep-alive");
        }
    }

    private static final Set<String> METHODS =
            Set.of("GET", "POST", "PUT", "DELETE", "PATCH", "HEAD", "OPTIONS");
    private static final int MAX_CHUNK_LINE = 1024;
    private static final int READ_CHUNK = 8192;
    private static final int MAX_LEADING_BREAKS = 8;

    private HttpParser() {}

    /**
     * Parses the next request.
     *
     * @return the request, or {@code null} if the stream ended before any byte arrived
     * @throws HttpException for malformed or oversized requests
     */
    public static ParsedRequest parse(InputStream in, Limits limits) throws IOException {
        byte[] head = readHead(in, limits);
        if (head == null) {
            return null;
        }
        String[] lines = new String(head, StandardCharsets.ISO_8859_1).split("\r\n");
        String[] parts = lines[0].split(" ", -1);
        if (parts.length != 3) {
            throw bad("Malformed request line");
        }
        String method = parts[0];
        String version = parts[2];
        if (!version.equals("HTTP/1.1") && !version.equals("HTTP/1.0")) {
            throw new HttpException(HttpStatus.VERSION_NOT_SUPPORTED, "Unsupported HTTP version");
        }
        if (!METHODS.contains(method)) {
            throw new HttpException(HttpStatus.NOT_IMPLEMENTED, "Unsupported method " + method);
        }
        String target = parts[1];
        if (!target.startsWith("/")) {
            throw bad("Request target must start with '/'");
        }
        int q = target.indexOf('?');
        String rawPath = q < 0 ? target : target.substring(0, q);
        String rawQuery = q < 0 ? "" : target.substring(q + 1);
        String path;
        try {
            path = Codec.percentDecode(rawPath, false);
        } catch (IllegalArgumentException e) {
            throw bad("Bad percent-encoding in path");
        }

        if (lines.length - 1 > limits.maxHeaderCount()) {
            throw new HttpException(HttpStatus.HEADER_FIELDS_TOO_LARGE, "Too many headers");
        }
        Map<String, String> headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        for (int i = 1; i < lines.length; i++) {
            int colon = lines[i].indexOf(':');
            if (colon <= 0) {
                throw bad("Malformed header line");
            }
            char first = lines[i].charAt(0);
            if (first == ' ' || first == '	') {
                throw bad("Obsolete line folding");
            }
            char last = lines[i].charAt(colon - 1);
            if (last == ' ' || last == '	') {
                throw bad("Whitespace before header colon");
            }
            String name = lines[i].substring(0, colon);
            String value = lines[i].substring(colon + 1).trim();
            checkNoControlChars(value);
            String sep = name.equalsIgnoreCase("Cookie") ? "; " : ", ";
            headers.merge(name, value, (a, b) -> a + sep + b);
        }

        byte[] body = readBody(in, headers, limits);
        return new ParsedRequest(method, path, rawQuery, version, headers, body);
    }

    private static byte[] readHead(InputStream in, Limits limits) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream(512);
        int tail = 0;
        int skipped = 0;
        int prev = -1;
        boolean inRequestLine = true;
        int lineLength = 0;
        while (true) {
            int b = in.read();
            if (b < 0) {
                if (buf.size() == 0) {
                    return null;
                }
                throw bad("Unexpected end of request");
            }
            if (buf.size() == 0 && (b == '\r' || b == '\n')) {
                if (++skipped > MAX_LEADING_BREAKS) {
                    throw bad("Too many empty lines before request line");
                }
                continue;
            }
            if (prev == '\r' && b != '\n' || b == '\n' && prev != '\r') {
                throw bad("Bare CR or LF in header block");
            }
            prev = b;
            buf.write(b);
            if (inRequestLine) {
                if (b == '\n') {
                    inRequestLine = false;
                } else if (b != '\r' && ++lineLength > limits.maxRequestLineBytes()) {
                    throw new HttpException(HttpStatus.URI_TOO_LONG, "Request line too long");
                }
            }
            if (buf.size() > limits.maxHeaderBytes()) {
                throw new HttpException(HttpStatus.HEADER_FIELDS_TOO_LARGE, "Headers too large");
            }
            tail = (tail << 8) | b;
            if (tail == 0x0D0A0D0A) {
                byte[] all = buf.toByteArray();
                return Arrays.copyOf(all, all.length - 4);
            }
        }
    }

    private static byte[] readBody(InputStream in, Map<String, String> headers, Limits limits)
            throws IOException {
        int max = limits.maxBodyBytes();
        String te = headers.get("Transfer-Encoding");
        if (te != null && headers.containsKey("Content-Length")) {
            throw bad("Content-Length with Transfer-Encoding");
        }
        if (te != null && headers.containsKey("Content-Length")) {
            throw bad("Content-Length with Transfer-Encoding");
        }
        if (te != null) {
            if (!te.equalsIgnoreCase("chunked")) {
                throw new HttpException(
                        HttpStatus.NOT_IMPLEMENTED, "Unsupported Transfer-Encoding");
            }
            return readChunked(in, limits);
        }
        String cl = headers.get("Content-Length");
        if (cl == null) {
            return new byte[0];
        }
        long len;
        try {
            len = Long.parseLong(cl);
        } catch (NumberFormatException e) {
            throw bad("Invalid Content-Length");
        }
        if (len < 0 || !cl.chars().allMatch(Character::isDigit)) {
            throw bad("Invalid Content-Length");
        }
        if (len > max) {
            throw new HttpException(HttpStatus.PAYLOAD_TOO_LARGE, "Body too large");
        }
        return readExactly(in, (int) len);
    }

    private static byte[] readExactly(InputStream in, int len) throws IOException {
        ByteArrayOutputStream body = new ByteArrayOutputStream(Math.min(len, READ_CHUNK));
        byte[] chunk = new byte[Math.min(len, READ_CHUNK)];
        int remaining = len;
        while (remaining > 0) {
            int n = in.read(chunk, 0, Math.min(remaining, chunk.length));
            if (n < 0) {
                throw new EOFException("Connection closed mid-body");
            }
            body.write(chunk, 0, n);
            remaining -= n;
        }
        return body.toByteArray();
    }

    private static byte[] readChunked(InputStream in, Limits limits) throws IOException {
        int max = limits.maxBodyBytes();
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        while (true) {
            String line = readLine(in);
            int semi = line.indexOf(';');
            String hex = semi < 0 ? line : line.substring(0, semi);
            if (!isHex(hex)) {
                throw bad("Invalid chunk size");
            }
            int size;
            try {
                size = Integer.parseInt(hex, 16);
            } catch (NumberFormatException e) {
                throw bad("Invalid chunk size");
            }
            if (size == 0) {
                skipTrailers(in, limits);
                return body.toByteArray();
            }
            if ((long) body.size() + size > max) {
                throw new HttpException(HttpStatus.PAYLOAD_TOO_LARGE, "Body too large");
            }
            body.writeBytes(readExactly(in, size));
            if (!readLine(in).isEmpty()) {
                throw bad("Malformed chunk");
            }
        }
    }

    private static void skipTrailers(InputStream in, Limits limits) throws IOException {
        int count = 0;
        int bytes = 0;
        String line;
        while (!(line = readLine(in)).isEmpty()) {
            bytes += line.length() + 2;
            if (++count > limits.maxHeaderCount() || bytes > limits.maxHeaderBytes()) {
                throw new HttpException(HttpStatus.HEADER_FIELDS_TOO_LARGE, "Trailers too large");
            }
        }
    }

    private static String readLine(InputStream in) throws IOException {
        StringBuilder sb = new StringBuilder();
        while (true) {
            int b = in.read();
            if (b < 0) {
                throw new EOFException("Connection closed mid-body");
            }
            if (b == '\n') {
                int n = sb.length();
                if (n == 0 || sb.charAt(n - 1) != '\r') {
                    throw bad("Bare LF in chunked framing");
                }
                sb.setLength(n - 1);
                return sb.toString();
            }
            sb.append((char) b);
            if (sb.length() > MAX_CHUNK_LINE) {
                throw bad("Chunk line too long");
            }
        }
    }

    private static boolean isHex(String s) {
        if (s.isEmpty()) {
            return false;
        }
        for (int i = 0; i < s.length(); i++) {
            if (Character.digit(s.charAt(i), 16) < 0 || s.charAt(i) > 'f') {
                return false;
            }
        }
        return true;
    }

    private static void checkNoControlChars(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < ' ' && c != '	' || c == 0x7f) {
                throw bad("Control character in header value");
            }
        }
    }

    private static HttpException bad(String msg) {
        return new HttpException(HttpStatus.BAD_REQUEST, msg);
    }
}
