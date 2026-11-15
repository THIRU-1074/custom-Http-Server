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

    private HttpParser() {}

    /**
     * Parses the next request.
     *
     * @return the request, or {@code null} if the stream ended before any byte arrived
     * @throws HttpException for malformed or oversized requests
     */
    public static ParsedRequest parse(InputStream in, Limits limits) throws IOException {
        byte[] head = readHead(in, limits.maxHeaderBytes());
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

        Map<String, String> headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        for (int i = 1; i < lines.length; i++) {
            int colon = lines[i].indexOf(':');
            if (colon <= 0) {
                throw bad("Malformed header line");
            }
            String name = lines[i].substring(0, colon).trim();
            String value = lines[i].substring(colon + 1).trim();
            headers.merge(name, value, (a, b) -> a + ", " + b);
        }

        byte[] body = readBody(in, headers, limits.maxBodyBytes());
        return new ParsedRequest(method, path, rawQuery, version, headers, body);
    }

    private static byte[] readHead(InputStream in, int max) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream(512);
        int tail = 0;
        while (true) {
            int b = in.read();
            if (b < 0) {
                if (buf.size() == 0) {
                    return null;
                }
                throw bad("Unexpected end of request");
            }
            if (buf.size() == 0 && (b == '\r' || b == '\n')) {
                continue;
            }
            buf.write(b);
            if (buf.size() > max) {
                throw new HttpException(HttpStatus.HEADER_FIELDS_TOO_LARGE, "Headers too large");
            }
            tail = (tail << 8) | b;
            if (tail == 0x0D0A0D0A) {
                byte[] all = buf.toByteArray();
                return Arrays.copyOf(all, all.length - 4);
            }
        }
    }

    private static byte[] readBody(InputStream in, Map<String, String> headers, int max)
            throws IOException {
        String te = headers.get("Transfer-Encoding");
        if (te != null) {
            if (!te.equalsIgnoreCase("chunked")) {
                throw new HttpException(
                        HttpStatus.NOT_IMPLEMENTED, "Unsupported Transfer-Encoding");
            }
            return readChunked(in, max);
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
        byte[] body = new byte[len];
        int off = 0;
        while (off < len) {
            int n = in.read(body, off, len - off);
            if (n < 0) {
                throw new EOFException("Connection closed mid-body");
            }
            off += n;
        }
        return body;
    }

    private static byte[] readChunked(InputStream in, int max) throws IOException {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        while (true) {
            String line = readLine(in);
            int semi = line.indexOf(';');
            String hex = (semi < 0 ? line : line.substring(0, semi)).trim();
            int size;
            try {
                size = Integer.parseInt(hex, 16);
            } catch (NumberFormatException e) {
                throw bad("Invalid chunk size");
            }
            if (size < 0) {
                throw bad("Invalid chunk size");
            }
            if (size == 0) {
                while (!readLine(in).isEmpty()) {
                    // trailers are read and ignored
                }
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

    private static String readLine(InputStream in) throws IOException {
        StringBuilder sb = new StringBuilder();
        while (true) {
            int b = in.read();
            if (b < 0) {
                throw new EOFException("Connection closed mid-body");
            }
            if (b == '\n') {
                int n = sb.length();
                if (n > 0 && sb.charAt(n - 1) == '\r') {
                    sb.setLength(n - 1);
                }
                return sb.toString();
            }
            sb.append((char) b);
            if (sb.length() > MAX_CHUNK_LINE) {
                throw bad("Chunk line too long");
            }
        }
    }

    private static HttpException bad(String msg) {
        return new HttpException(HttpStatus.BAD_REQUEST, msg);
    }
}
