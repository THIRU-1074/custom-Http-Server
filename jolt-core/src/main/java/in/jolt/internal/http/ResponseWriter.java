package in.jolt.internal.http;

import in.jolt.HttpStatus;
import in.jolt.Response;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;

/** Serializes a {@link Response} as an HTTP/1.1 message. */
public final class ResponseWriter {

    private ResponseWriter() {}

    /**
     * Writes the response in a single flush. Framing headers ({@code Content-Length}, {@code
     * Connection}, {@code Transfer-Encoding}) are always set by the writer, never by the handler.
     */
    public static void write(OutputStream out, Response res, boolean keepAlive, boolean headOnly)
            throws IOException {
        int status = res.statusCode();
        boolean noBody = status == 204 || status == 304 || status < 200;
        byte[] body = res.body();
        StringBuilder sb = head(res);
        if (!noBody) {
            sb.append("Content-Length: ").append(body.length).append("\r\n");
        }
        sb.append("Connection: ").append(keepAlive ? "keep-alive" : "close").append("\r\n\r\n");

        ByteArrayOutputStream buf = new ByteArrayOutputStream(sb.length() + body.length);
        buf.writeBytes(sb.toString().getBytes(StandardCharsets.ISO_8859_1));
        if (!noBody && !headOnly) {
            buf.writeBytes(body);
        }
        out.write(buf.toByteArray());
        out.flush();
    }

    /** Writes the status line and headers of a chunked response that ends the connection. */
    public static void writeStreamHead(OutputStream out, Response res) throws IOException {
        StringBuilder sb = head(res);
        sb.append("Transfer-Encoding: chunked\r\nConnection: close\r\n\r\n");
        out.write(sb.toString().getBytes(StandardCharsets.ISO_8859_1));
        out.flush();
    }

    private static StringBuilder head(Response res) {
        int status = res.statusCode();
        StringBuilder sb = new StringBuilder(256);
        sb.append("HTTP/1.1 ").append(status).append(' ').append(HttpStatus.reasonFor(status));
        sb.append("\r\n");
        for (Map.Entry<String, String> h : res.headers()) {
            String name = h.getKey();
            if (name.equalsIgnoreCase("Content-Length")
                    || name.equalsIgnoreCase("Connection")
                    || name.equalsIgnoreCase("Transfer-Encoding")) {
                continue;
            }
            sb.append(name).append(": ").append(h.getValue()).append("\r\n");
        }
        return sb.append("Date: ")
                .append(
                        DateTimeFormatter.RFC_1123_DATE_TIME.format(
                                ZonedDateTime.now(ZoneOffset.UTC)))
                .append("\r\n");
    }
}
